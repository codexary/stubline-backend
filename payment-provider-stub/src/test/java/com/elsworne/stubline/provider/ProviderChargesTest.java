package com.elsworne.stubline.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.http.JdkHttpServer;
import com.elsworne.stubline.http.TicketingApi;
import com.elsworne.stubline.inventory.InMemoryInventory;
import com.elsworne.stubline.payments.InMemoryIdempotencyStore;
import com.elsworne.stubline.payments.PaymentProviderClient;
import com.elsworne.stubline.payments.PaymentService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderChargesTest {
  private final Clock clock = Clock.systemUTC();
  private static final String BODY = "{\"orderId\":\"o1\",\"amountCents\":500}";

  @Test
  void providerDeduplicatesSameKeyAndRejectsChangedPayment() throws Exception {
    var charges = new ProviderCharges();
    try (var server = provider(charges);
        var http = HttpClient.newHttpClient()) {
      var uri = URI.create("http://localhost:" + server.port() + "/payments");
      assertEquals(
          201,
          http.send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          201,
          http.send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          422,
          http.send(
                  request(uri, "{\"orderId\":\"o1\",\"amountCents\":600}", Duration.ofSeconds(2)),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(1, charges.count());
    }
  }

  @Test
  void definiteDeclineDoesNotStoreCharge() throws Exception {
    var charges = new ProviderCharges();
    charges.declineNext();
    try (var server = provider(charges);
        var http = HttpClient.newHttpClient()) {
      var uri = URI.create("http://localhost:" + server.port() + "/payments");
      assertEquals(
          402,
          http.send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(0, charges.count());
      assertEquals(
          201,
          http.send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(1, charges.count());
    }
  }

  @Test
  void apiReturnsDeclineAndReleasesKey() throws Exception {
    var charges = new ProviderCharges();
    charges.declineNext();
    try (var provider = provider(charges);
        var upstream =
            new PaymentProviderClient(URI.create("http://localhost:" + provider.port()));
        var api = api(upstream);
        var client = HttpClient.newHttpClient()) {
      api.start();
      var uri = URI.create("http://localhost:" + api.port() + "/payments");
      assertEquals(
          402,
          client
              .send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          201,
          client
              .send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(1, charges.count());
    }
  }

  @Test
  void transientProviderFailureStillReturnsDefiniteDeclineAndReleasesKey() throws Exception {
    var charges = new ProviderCharges();
    charges.declineNext();
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/payments",
        exchange -> {
          if (calls.getAndIncrement() == 0) {
            try (exchange) {
              exchange.sendResponseHeaders(503, -1);
            }
          } else {
            charges.handle(exchange);
          }
        });
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    try (var provider = new ProviderServer(server);
        var upstream =
            new PaymentProviderClient(URI.create("http://localhost:" + provider.port()));
        var api = api(upstream);
        var client = HttpClient.newHttpClient()) {
      api.start();
      var uri = URI.create("http://localhost:" + api.port() + "/payments");
      assertEquals(
          402,
          client
              .send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(
          201,
          client
              .send(request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(3, calls.get());
      assertEquals(1, charges.count());
    }
  }

  @Test
  void providerMismatchAfterApiRecordLossReturns422() throws Exception {
    var charges = new ProviderCharges();
    try (var provider = provider(charges);
        var upstream =
            new PaymentProviderClient(URI.create("http://localhost:" + provider.port()));
        var client = HttpClient.newHttpClient()) {
      try (var api = api(upstream)) {
        api.start();
        var uri = URI.create("http://localhost:" + api.port() + "/payments");
        assertEquals(
            201,
            client
                .send(
                    request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
      try (var restarted = api(upstream)) {
        restarted.start();
        var uri = URI.create("http://localhost:" + restarted.port() + "/payments");
        assertEquals(
            422,
            client
                .send(
                    request(uri, "{\"orderId\":\"o1\",\"amountCents\":600}", Duration.ofSeconds(2)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(1, charges.count());
      }
    }
  }

  @Test
  void lostFirstReplyAndLostApiRecordStillChargeOnce() throws Exception {
    var charges = new ProviderCharges(350);
    try (var provider = provider(charges);
        var client = HttpClient.newHttpClient();
        var upstream =
            new PaymentProviderClient(URI.create("http://localhost:" + provider.port()))) {
      try (var api = api(upstream)) {
        api.start();
        var uri = URI.create("http://localhost:" + api.port() + "/payments");
        assertThrows(
            HttpTimeoutException.class,
            () ->
                client.send(
                    request(uri, BODY, Duration.ofMillis(100)),
                    HttpResponse.BodyHandlers.ofString()));
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (charges.count() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, charges.count());
        Thread.sleep(400);
      }
      try (var restarted = api(upstream)) {
        restarted.start();
        var uri = URI.create("http://localhost:" + restarted.port() + "/payments");
        assertEquals(
            201,
            client
                .send(
                    request(uri, BODY, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(1, charges.count());
      }
    }
  }

  private JdkHttpServer api(PaymentProviderClient provider) throws Exception {
    return new JdkHttpServer(
        0,
        new TicketingApi(
            new InMemoryInventory(),
            new PaymentService(new InMemoryIdempotencyStore(clock), provider, clock)));
  }

  private static ProviderServer provider(ProviderCharges charges) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/payments", charges::handle);
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return new ProviderServer(server);
  }

  private static HttpRequest request(URI uri, String body, Duration timeout) {
    return HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Idempotency-Key", "key-1")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  private record ProviderServer(HttpServer server) implements AutoCloseable {
    int port() {
      return server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
