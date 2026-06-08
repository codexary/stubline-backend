package com.elsworne.stubline.payments;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies a shared request deadline, safe retries and a circuit breaker to provider calls. */
public final class PaymentProviderClient implements AutoCloseable, ChargeProvider {
  private static final Logger LOG = LoggerFactory.getLogger(PaymentProviderClient.class);
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
                .recordException(error -> !(error instanceof CallNotPermittedException))
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
    return invoke("/charge?orderId=" + orderId, false, budget);
  }

  @Override
  public String charge(String key, String orderId, long amountCents) {
    try {
      String body = "{\"orderId\":\"" + orderId + "\",\"amountCents\":" + amountCents + "}";
      var request =
          HttpRequest.newBuilder(base.resolve("/payments"))
              .timeout(Duration.ofSeconds(2))
              .header("Idempotency-Key", key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 422)
        throw new IllegalArgumentException("Provider key reused with different payment");
      if (response.statusCode() == 402)
        throw new PaymentProviderStub.ChargeNotMadeException("Provider declined before charge");
      if (response.statusCode() != 201)
        throw new IllegalStateException("Provider HTTP " + response.statusCode());
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(response.body())
          .path("chargeId")
          .asText();
    } catch (IOException e) {
      throw new IllegalStateException("Unknown provider charge outcome", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted provider charge outcome", e);
    }
  }

  public String chargeStatus(String orderId, Duration budget)
      throws IOException, InterruptedException {
    return invoke("/charge-status?orderId=" + orderId, true, budget);
  }

  private String invoke(String path, boolean repeatable, Duration budget)
      throws IOException, InterruptedException {
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
          var response = http.send(request, HttpResponse.BodyHandlers.ofString());
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

  @Override
  public void close() {
    http.close();
  }
}
