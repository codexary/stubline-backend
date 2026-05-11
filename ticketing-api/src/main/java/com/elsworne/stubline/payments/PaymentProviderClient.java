package com.elsworne.stubline.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies a shared request deadline, safe retries and a circuit breaker to provider calls. */
public final class PaymentProviderClient implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(PaymentProviderClient.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http;
  private final URI base;
  private final CircuitBreaker breaker;
  private final DoubleSupplier random;
  private final long baseBackoffMillis;
  private final java.util.function.LongConsumer sleeper;
  private final java.util.List<String> transitions =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  public PaymentProviderClient(URI base) {
    this(base, ThreadLocalRandom.current()::nextDouble, 100, Duration.ofSeconds(3));
  }

  public PaymentProviderClient(
      URI base, DoubleSupplier random, long baseBackoffMillis, Duration openFor) {
    this(
        base,
        random,
        baseBackoffMillis,
        openFor,
        millis -> {
          try {
            Thread.sleep(millis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  PaymentProviderClient(
      URI base,
      DoubleSupplier random,
      long baseBackoffMillis,
      Duration openFor,
      java.util.function.LongConsumer sleeper) {
    this.base = Objects.requireNonNull(base);
    this.random = Objects.requireNonNull(random);
    this.baseBackoffMillis = baseBackoffMillis;
    this.sleeper = sleeper;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    this.breaker =
        CircuitBreaker.of(
            "payment-provider",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(
                    error ->
                        error instanceof IOException
                            && !(error instanceof ProviderRejectedException))
                .build());
    breaker
        .getEventPublisher()
        .onStateTransition(
            event -> {
              transitions.add(event.getStateTransition().toString());
              LOG.info("Payment provider breaker {}", event.getStateTransition());
            });
  }

  public CircuitBreaker.State state() {
    return breaker.getState();
  }

  public java.util.List<String> transitions() {
    return java.util.List.copyOf(transitions);
  }

  public String charge(String orderId, Duration budget) throws IOException, InterruptedException {
    return invoke("/charge?orderId=" + encode(orderId), false, budget);
  }

  public String chargeStatus(String orderId, Duration budget)
      throws IOException, InterruptedException {
    return invoke("/charge-status?orderId=" + encode(orderId), true, budget);
  }

  private String invoke(String path, boolean repeatable, Duration budget)
      throws IOException, InterruptedException {
    if (budget == null || budget.isZero() || budget.isNegative())
      throw new IllegalArgumentException("Deadline budget must be positive");
    long deadline = System.nanoTime() + budget.toNanos();
    int attempts = repeatable ? 3 : 1;
    IOException last = null;
    for (int attempt = 0; attempt < attempts; attempt++) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0)
        throw new java.net.http.HttpTimeoutException("Request deadline exhausted");
      try {
        breaker.acquirePermission();
        long started = System.nanoTime();
        try {
          var builder =
              HttpRequest.newBuilder(base.resolve(path))
                  .timeout(Duration.ofNanos(Math.max(1, remaining)));
          var request =
              (repeatable ? builder.GET() : builder.POST(HttpRequest.BodyPublishers.noBody()))
                  .build();
          var response = sendWithin(request, deadline);
          if (response.statusCode() >= 400 && response.statusCode() < 500)
            throw new ProviderRejectedException(response.statusCode());
          if (response.statusCode() >= 500)
            throw new IOException("Provider HTTP " + response.statusCode());
          breaker.onSuccess(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
          return response.body();
        } catch (IOException | InterruptedException e) {
          breaker.onError(
              System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS, e);
          throw e;
        }
      } catch (CallNotPermittedException e) {
        throw new IOException("Payment provider circuit open", e);
      } catch (ProviderRejectedException e) {
        throw e;
      } catch (IOException e) {
        last = e;
        if (attempt + 1 == attempts) throw e;
      }
      long cap = Math.multiplyExact(baseBackoffMillis, 1L << attempt);
      long delay = (long) (random.getAsDouble() * cap);
      long availableMillis =
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
              Math.max(0, deadline - System.nanoTime()));
      if (availableMillis <= 0)
        throw new java.net.http.HttpTimeoutException("Request deadline exhausted");
      sleeper.accept(Math.min(delay, availableMillis));
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    throw last;
  }

  private static String encode(String value) {
    return URLEncoder.encode(Objects.requireNonNull(value), StandardCharsets.UTF_8);
  }

  private HttpResponse<String> sendWithin(HttpRequest request, long deadline)
      throws IOException, InterruptedException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new java.net.http.HttpTimeoutException("Request deadline exhausted");
    var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    try {
      return pending.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new java.net.http.HttpTimeoutException("Request deadline exhausted");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException io) throw io;
      throw new IOException("Provider exchange failed", e.getCause());
    } catch (InterruptedException e) {
      pending.cancel(true);
      throw e;
    }
  }

  /** A definitive provider response; no retry or breaker failure is warranted. */
  public static final class ProviderRejectedException extends IOException {
    private final int status;

    ProviderRejectedException(int status) {
      super("Provider rejected charge: HTTP " + status);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  @Override
  public void close() {
    http.close();
  }
}
