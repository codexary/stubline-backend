package com.elsworne.stubline.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PaymentProviderClientTest {
  @Test
  void slowProviderCannotOutliveRequestDeadline() throws Exception {
    try (var server = stub(500, 200)) {
      try (var client =
          new PaymentProviderClient(server.uri(), () -> 0, 1, Duration.ofSeconds(1))) {
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> client.charge("x", Duration.ofMillis(120)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 450);
      }
    }
  }

  @Test
  void chargeIsNeverRetried() throws Exception {
    try (var server = stub(0, 503);
        var client = new PaymentProviderClient(server.uri(), () -> 0, 1, Duration.ofSeconds(1))) {
      assertThrows(IOException.class, () -> client.charge("x", Duration.ofSeconds(2)));
      assertEquals(1, server.calls());
    }
  }

  @Test
  void chargeStatusRetriesFailures() throws Exception {
    try (var server = stub(0, 503);
        var client = new PaymentProviderClient(server.uri(), () -> 0, 1, Duration.ofSeconds(1))) {
      assertThrows(IOException.class, () -> client.chargeStatus("x", Duration.ofSeconds(2)));
      assertEquals(3, server.calls());
    }
  }

  @Test
  void statusBackoffGrowsAndUsesInjectedJitter() throws Exception {
    var delays = new java.util.ArrayList<Long>();
    try (var server = stub(0, 503);
        var client =
            new PaymentProviderClient(
                server.uri(), () -> 0.5, 100, Duration.ofSeconds(1), delays::add)) {
      assertThrows(IOException.class, () -> client.chargeStatus("x", Duration.ofSeconds(2)));
      assertEquals(java.util.List.of(50L, 100L), delays);
    }
  }

  @Test
  void breakerOpensRejectsFastThenHalfOpensAndCloses() throws Exception {
    try (var server = stub(0, 503);
        var client = new PaymentProviderClient(server.uri(), () -> 0, 1, Duration.ofMillis(150))) {
      for (int i = 0; i < 4; i++)
        assertThrows(IOException.class, () -> client.charge("x", Duration.ofSeconds(1)));
      assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, client.state());
      int calls = server.calls();
      assertThrows(IOException.class, () -> client.charge("x", Duration.ofSeconds(1)));
      assertEquals(calls, server.calls());
      server.status.set(200);
      Thread.sleep(180);
      assertEquals("{\"status\":\"approved\"}", client.charge("x", Duration.ofSeconds(1)));
      assertEquals(
          io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, client.state());
      assertTrue(client.transitions().stream().anyMatch(t -> t.contains("OPEN to HALF_OPEN")));
    }
  }

  private static Stub stub(long delay, int status) throws IOException {
    return new Stub(delay, status);
  }

  private static final class Stub implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status;

    Stub(long delay, int initialStatus) throws IOException {
      status = new AtomicInteger(initialStatus);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            calls.incrementAndGet();
            try {
              Thread.sleep(delay);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            byte[] body =
                "{\"status\":\"approved\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (exchange) {
              exchange.sendResponseHeaders(status.get(), body.length);
              exchange.getResponseBody().write(body);
            }
          });
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    int calls() {
      return calls.get();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  @Test
  void providerDeclineIsDefiniteFailureWithoutRetry() throws Exception {
    try (var server = stub(0, 402);
        var client = new PaymentProviderClient(server.uri(), () -> 0, 1, Duration.ofSeconds(1))) {
      assertThrows(IOException.class, () -> client.charge("x", Duration.ofSeconds(1)));
      assertEquals(1, server.calls());
    }
  }

  @Test
  void providerRejectionsDoNotCountAsBreakerFailures() throws Exception {
    try (var server = stub(0, 402);
        var client = new PaymentProviderClient(server.uri(), () -> 0, 1, Duration.ofSeconds(1))) {
      for (int i = 0; i < 4; i++) {
        var rejection =
            assertThrows(
                PaymentProviderClient.ProviderRejectedException.class,
                () -> client.charge("x", Duration.ofSeconds(1)));
        assertEquals(402, rejection.status());
      }
      assertEquals(4, server.calls());
      assertEquals(
          io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, client.state());
      server.status.set(503);
      for (int i = 0; i < 2; i++)
        assertThrows(IOException.class, () -> client.charge("x", Duration.ofSeconds(1)));
      assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, client.state());
    }
  }

  @Test
  void orderIdIsEncodedInProviderUrl() throws Exception {
    var seen = new java.util.concurrent.atomic.AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/charge",
        exchange -> {
          seen.set(exchange.getRequestURI().getRawQuery());
          byte[] body = "ok".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          try (exchange) {
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try (var client =
        new PaymentProviderClient(
            URI.create("http://localhost:" + server.getAddress().getPort()))) {
      assertEquals("ok", client.charge("a&b /?", Duration.ofSeconds(1)));
      assertEquals("orderId=a%26b+%2F%3F", seen.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void slowResponseBodyCannotOutliveDeadline() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/charge",
        exchange -> {
          exchange.sendResponseHeaders(200, 2);
          try (exchange) {
            exchange.getResponseBody().write('a');
            exchange.getResponseBody().flush();
            try {
              Thread.sleep(400);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            exchange.getResponseBody().write('b');
          } catch (IOException ignored) {
          }
        });
    server.start();
    try (var client =
        new PaymentProviderClient(
            URI.create("http://localhost:" + server.getAddress().getPort()))) {
      long start = System.nanoTime();
      assertThrows(IOException.class, () -> client.charge("x", Duration.ofMillis(100)));
      assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 300);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsExhaustedDeadlineBeforeSending() throws Exception {
    try (var server = stub(0, 200);
        var client = new PaymentProviderClient(server.uri())) {
      assertThrows(IllegalArgumentException.class, () -> client.charge("x", Duration.ZERO));
      assertEquals(0, server.calls());
    }
  }
}
