package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.elsworne.stubline.inventory.InMemoryInventory;
import com.elsworne.stubline.issuer.DevTokenIssuer;
import com.elsworne.stubline.orders.Order;
import com.elsworne.stubline.payments.InMemoryIdempotencyStore;
import com.elsworne.stubline.payments.PaymentService;
import com.elsworne.stubline.tickets.TicketRepository;
import com.elsworne.stubline.tickets.TicketService;
import com.elsworne.stubline.tickets.TicketStorage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Route-level authentication and buyer ownership decisions. */
final class BuyerAuthorizationTest {
  @Test
  void missingTokenHasChallenge() throws Exception {
    try (Fixture f = new Fixture()) {
      var reply =
          f.api.handle(
              new HttpRequest("POST", "/orders", Map.of(), "{}".getBytes(StandardCharsets.UTF_8)));
      assertEquals(401, reply.status());
      assertEquals("Bearer realm=\"stubline\"", reply.headers().get("WWW-Authenticate"));
      assertTrue(new String(reply.body(), StandardCharsets.UTF_8).contains("\"status\":401"));
      assertEquals("application/problem+json", reply.headers().get("Content-Type"));
    }
  }

  @Test
  void invalidTokenHasInvalidTokenChallenge() throws Exception {
    try (Fixture f = new Fixture()) {
      var reply =
          f.api.handle(
              new HttpRequest(
                  "POST", "/orders", Map.of("authorization", "Bearer invalid"), new byte[0]));
      assertEquals(401, reply.status());
      assertTrue(reply.headers().get("WWW-Authenticate").contains("error=\"invalid_token\""));
      assertTrue(reply.headers().get("WWW-Authenticate").contains("error_description="));
    }
  }

  @Test
  void orderLookupRequiresOwnerAndHidesOtherOrders() throws Exception {
    try (Fixture f = new Fixture()) {
      var missing = f.api.handle(new HttpRequest("GET", "/orders/order-1", Map.of(), new byte[0]));
      assertEquals(401, missing.status());
      var owner =
          f.api.handle(
              new HttpRequest(
                  "GET",
                  "/orders/order-1",
                  Map.of("authorization", "Bearer " + f.issuer.issue("buyer-a")),
                  new byte[0]));
      assertEquals(200, owner.status());
      OpenApiContractTest.checkOrderLookup(owner);
      assertTrue(
          new String(owner.body(), StandardCharsets.UTF_8).contains("\"eventId\":\"concert-1\""));
      var stranger =
          f.api.handle(
              new HttpRequest(
                  "GET",
                  "/orders/order-1",
                  Map.of("authorization", "Bearer " + f.issuer.issue("buyer-b")),
                  new byte[0]));
      assertEquals(404, stranger.status());
      assertEquals("application/problem+json", stranger.headers().get("Content-Type"));
      OpenApiContractTest.checkOrderLookup(stranger);
      var unknown =
          f.api.handle(
              new HttpRequest(
                  "GET",
                  "/orders/unknown",
                  Map.of("authorization", "Bearer " + f.issuer.issue("buyer-a")),
                  new byte[0]));
      assertEquals(404, unknown.status());
    }
  }

  @Test
  void anotherBuyerCannotReadTicket() throws Exception {
    try (Fixture f = new Fixture()) {
      var reply =
          f.api.handle(
              new HttpRequest(
                  "GET",
                  "/orders/order-1/ticket",
                  Map.of("authorization", "Bearer " + f.issuer.issue("buyer-b")),
                  new byte[0]));
      assertEquals(403, reply.status());
    }
  }

  @Test
  void anotherBuyerCannotPayForOrder() throws Exception {
    try (Fixture f = new Fixture()) {
      var reply =
          f.api.handle(
              new HttpRequest(
                  "POST",
                  "/payments",
                  Map.of(
                      "authorization",
                      "Bearer " + f.issuer.issue("buyer-b"),
                      "idempotency-key",
                      "other-buyer"),
                  "{\"orderId\":\"order-1\",\"amountCents\":2500}"
                      .getBytes(StandardCharsets.UTF_8)));
      assertEquals(403, reply.status());
      assertEquals("application/problem+json", reply.headers().get("Content-Type"));
      OpenApiContractTest.checkPaymentForbidden(reply);
    }
  }

  @Test
  void ownerCanPayForOrder() throws Exception {
    try (Fixture f = new Fixture()) {
      var reply =
          f.api.handle(
              new HttpRequest(
                  "POST",
                  "/payments",
                  Map.of(
                      "authorization",
                      "Bearer " + f.issuer.issue("buyer-a"),
                      "idempotency-key",
                      "owner-payment"),
                  "{\"orderId\":\"order-1\",\"amountCents\":2500}"
                      .getBytes(StandardCharsets.UTF_8)));
      assertEquals(201, reply.status());
    }
  }

  @Test
  void paymentsFailClosedWithoutOwnershipStore() throws Exception {
    try (Fixture f = new Fixture()) {
      var api =
          new TicketingApi(
              new InMemoryInventory(),
              0,
              null,
              new PaymentService(
                  new InMemoryIdempotencyStore(Clock.systemUTC()),
                  (key, orderId, amount) -> "charge-1",
                  Clock.systemUTC()),
              null);
      api.requireBuyerTokens(
          new BuyerTokens(
              URI.create("http://localhost:" + f.jwks.getAddress().getPort() + "/jwks"),
              "https://dev-token-issuer:8443",
              "stubline-api",
              Clock.systemUTC()));
      var reply =
          api.handle(
              new HttpRequest(
                  "POST",
                  "/payments",
                  Map.of("authorization", "Bearer " + f.issuer.issue("buyer-a")),
                  "{\"orderId\":\"order-1\",\"amountCents\":2500}"
                      .getBytes(StandardCharsets.UTF_8)));
      assertEquals(503, reply.status());
    }
  }

  @Test
  void ownerCanReadTicket() throws Exception {
    try (Fixture f = new Fixture()) {
      var reply =
          f.api.handle(
              new HttpRequest(
                  "GET",
                  "/orders/order-1/ticket",
                  Map.of("authorization", "Bearer " + f.issuer.issue("buyer-a")),
                  new byte[0]));
      assertEquals(200, reply.status());
    }
  }

  @Test
  void rejectedPreflightIsProblemDetails() throws Exception {
    try (Fixture f = new Fixture()) {
      f.api.configureCors("https://buyer.example");
      var reply =
          f.api.handle(
              new HttpRequest(
                  "OPTIONS",
                  "/orders",
                  Map.of(
                      "origin", "https://other.example", "access-control-request-method", "POST"),
                  new byte[0]));
      assertEquals(403, reply.status());
      assertEquals("application/problem+json", reply.headers().get("Content-Type"));
      assertTrue(new String(reply.body(), StandardCharsets.UTF_8).contains("\"status\":403"));
    }
  }

  @Test
  void securityHeadersAndCorsAllowlist() throws Exception {
    try (Fixture f = new Fixture()) {
      f.api.configureCors("https://buyer.example");
      var preflight =
          f.api.handle(
              new HttpRequest(
                  "OPTIONS",
                  "/orders",
                  Map.of(
                      "origin",
                      "https://buyer.example",
                      "access-control-request-method",
                      "POST",
                      "access-control-request-headers",
                      "Authorization, Content-Type, Idempotency-Key"),
                  new byte[0]));
      assertEquals(204, preflight.status());
      assertEquals("https://buyer.example", preflight.headers().get("Access-Control-Allow-Origin"));
      assertEquals("nosniff", preflight.headers().get("X-Content-Type-Options"));
      assertTrue(preflight.headers().containsKey("Strict-Transport-Security"));
      var rejected =
          f.api.handle(
              new HttpRequest(
                  "OPTIONS",
                  "/orders",
                  Map.of(
                      "origin", "https://other.example", "access-control-request-method", "POST"),
                  new byte[0]));
      assertEquals(403, rejected.status());
      assertTrue(!rejected.headers().containsKey("Access-Control-Allow-Origin"));
      var authenticated =
          f.api.handle(
              new HttpRequest(
                  "GET",
                  "/orders/order-1/ticket",
                  Map.of("authorization", "Bearer " + f.issuer.issue("buyer-a")),
                  new byte[0]));
      assertEquals("no-store", authenticated.headers().get("Cache-Control"));
    }
  }

  private static final class Fixture implements AutoCloseable {
    final DevTokenIssuer issuer = new DevTokenIssuer();
    final HttpServer jwks = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    final TicketingApi api;

    Fixture() throws Exception {
      jwks.createContext(
          "/jwks",
          exchange -> {
            byte[] body = issuer.jwks().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      jwks.start();
      TicketRepository repository =
          new TicketRepository() {
            @Override
            public Optional<Order> find(String id) {
              return id.equals("order-1")
                  ? Optional.of(new Order("order-1", "concert-1", "A1", java.time.Instant.EPOCH))
                  : Optional.empty();
            }

            @Override
            public Optional<String> ticketKey(String id) {
              return Optional.of("tickets/order-1.pdf");
            }

            @Override
            public void markTicket(String id, String key) {}

            @Override
            public boolean ownedBy(String id, String subject) {
              return id.equals("order-1") && subject.equals("buyer-a");
            }
          };
      TicketStorage storage =
          new TicketStorage() {
            @Override
            public void put(String key, byte[] pdf) {}

            @Override
            public URI presignGet(String key, Duration lifetime) {
              return URI.create("https://tickets.example/order-1");
            }
          };
      api =
          new TicketingApi(
              new InMemoryInventory(),
              0,
              null,
              new PaymentService(
                  new InMemoryIdempotencyStore(Clock.systemUTC()),
                  (key, orderId, amount) -> "charge-1",
                  Clock.systemUTC()),
              null,
              new TicketService(repository, storage));
      api.requireBuyerTokens(
          new BuyerTokens(
              URI.create("http://localhost:" + jwks.getAddress().getPort() + "/jwks"),
              "https://dev-token-issuer:8443",
              "stubline-api",
              Clock.systemUTC()));
    }

    @Override
    public void close() {
      jwks.stop(0);
    }
  }
}
