package com.elsworne.stubline.http;

import com.elsworne.stubline.diagnostics.DnsDiagnostics;
import com.elsworne.stubline.inventory.InMemoryInventory;
import com.elsworne.stubline.inventory.Seat;
import com.elsworne.stubline.inventory.SeatInventory;
import com.elsworne.stubline.orders.Order;
import com.elsworne.stubline.orders.outbox.OrderStore;
import com.elsworne.stubline.payments.PaymentProviderClient;
import com.elsworne.stubline.payments.PaymentProviderStub;
import com.elsworne.stubline.payments.PaymentService;
import com.elsworne.stubline.tickets.TicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Transport-independent seat and order routes. */
public final class TicketingApi {
  private final ObjectMapper json = new ObjectMapper();
  private final DnsDiagnostics dns = new DnsDiagnostics();
  private final SeatInventory inventory;
  private final PaymentProviderClient payments;
  private final PaymentService paymentService;
  private final long lookupDelayMillis;
  private final OrderStore orderStore;
  private TicketService ticketService;
  private BuyerTokens buyerTokens;

  public void requireBuyerTokens(BuyerTokens tokens) {
    this.buyerTokens = tokens;
  }

  private final java.util.concurrent.atomic.AtomicInteger inFlight =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.concurrent.atomic.AtomicInteger peakInFlight =
      new java.util.concurrent.atomic.AtomicInteger();

  public TicketingApi(SeatInventory inventory) {
    this(inventory, 0, null);
  }

  public TicketingApi(SeatInventory inventory, long lookupDelayMillis) {
    this(inventory, lookupDelayMillis, null);
  }

  public TicketingApi(
      SeatInventory inventory, long lookupDelayMillis, PaymentProviderClient payments) {
    this.payments = payments;
    this.paymentService = null;
    this.orderStore = null;
    if (lookupDelayMillis < 0) throw new IllegalArgumentException("Negative lookup delay");
    this.inventory = inventory;
    this.lookupDelayMillis = lookupDelayMillis;
  }

  public TicketingApi(
      SeatInventory inventory,
      long lookupDelayMillis,
      PaymentProviderClient payments,
      PaymentService paymentService) {
    if (lookupDelayMillis < 0) throw new IllegalArgumentException("Negative lookup delay");
    this.inventory = inventory;
    this.lookupDelayMillis = lookupDelayMillis;
    this.payments = payments;
    this.paymentService = paymentService;
    this.orderStore = null;
  }

  public TicketingApi(
      SeatInventory inventory,
      long lookupDelayMillis,
      PaymentProviderClient payments,
      PaymentService paymentService,
      OrderStore orderStore) {
    this.inventory = inventory;
    this.lookupDelayMillis = lookupDelayMillis;
    this.payments = payments;
    this.paymentService = paymentService;
    this.orderStore = orderStore;
  }

  public TicketingApi(
      SeatInventory inventory,
      long lookupDelayMillis,
      PaymentProviderClient payments,
      PaymentService paymentService,
      OrderStore orderStore,
      TicketService ticketService) {
    this(inventory, lookupDelayMillis, payments, paymentService, orderStore);
    this.ticketService = ticketService;
  }

  public TicketingApi(SeatInventory inventory, PaymentService paymentService) {
    this.inventory = inventory;
    this.lookupDelayMillis = 0;
    this.payments = null;
    this.paymentService = paymentService;
    this.orderStore = null;
  }

  public HttpReply handle(HttpRequest request) {
    try {
      String subject = null;
      boolean protectedRoute =
          request.method().equals("POST")
                  && (request.path().equals("/orders") || request.path().equals("/payments"))
              || request.method().equals("GET") && request.path().matches("/orders/[^/]+/ticket");
      if (buyerTokens != null && protectedRoute) {
        String authorization = request.header("authorization");
        subject = buyerTokens.subject(authorization);
        if (subject == null)
          return new HttpReply(
              401,
              "Unauthorized",
              (authorization == null || authorization.isBlank()
                      ? ""
                      : "{\"error\":\"invalid_token\"}")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8),
              Map.of(
                  "WWW-Authenticate",
                  authorization == null || authorization.isBlank()
                      ? "Bearer realm=\"stubline\""
                      : "Bearer realm=\"stubline\", error=\"invalid_token\", error_description=\"Invalid or expired bearer token\""));
      }
      if ((request.method().equals("GET") || request.method().equals("HEAD"))
          && request.path().equals("/health"))
        return HttpReply.json(200, "OK", "{\"status\":\"ok\"}");
      if (request.method().equals("GET") && request.path().matches("/events/[^/]+/seats")) {
        String eventId = request.path().split("/")[2];
        int now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
        try {
          if (lookupDelayMillis > 0) Thread.sleep(lookupDelayMillis);
        } finally {
          inFlight.decrementAndGet();
        }
        return new HttpReply(200, "OK", json.writeValueAsBytes(inventory.seatsFor(eventId)));
      }
      if (request.method().equals("GET") && request.path().startsWith("/diagnostics/dns?name=")) {
        String name =
            java.net.URLDecoder.decode(
                request.path().substring("/diagnostics/dns?name=".length()),
                java.nio.charset.StandardCharsets.UTF_8);
        try {
          return new HttpReply(200, "OK", json.writeValueAsBytes(dns.report(name)));
        } catch (java.net.UnknownHostException e) {
          return HttpReply.json(404, "Not Found", "{\"error\":\"name not found\"}");
        } catch (IllegalArgumentException e) {
          return HttpReply.json(400, "Bad Request", "{\"error\":\"invalid DNS name\"}");
        }
      }
      if (request.method().equals("GET") && request.path().equals("/diagnostics/runtime")) {
        long memoryLimit = -1;
        try {
          String raw =
              java.nio.file.Files.readString(java.nio.file.Path.of("/sys/fs/cgroup/memory.max"))
                  .trim();
          if (!raw.equals("max")) memoryLimit = Long.parseLong(raw);
        } catch (IOException | NumberFormatException ignored) {
        }
        return HttpReply.json(
            200,
            "OK",
            "{\"availableProcessors\":"
                + Runtime.getRuntime().availableProcessors()
                + ",\"maxHeapBytes\":"
                + Runtime.getRuntime().maxMemory()
                + ",\"containerMemoryLimitBytes\":"
                + memoryLimit
                + "}");
      }
      if (request.method().equals("GET") && request.path().equals("/diagnostics/thread"))
        return HttpReply.json(
            200, "OK", "{\"virtual\":" + Thread.currentThread().isVirtual() + "}");
      if (request.method().equals("GET") && request.path().matches("/orders/[^/]+/ticket")) {
        if (ticketService == null)
          return HttpReply.json(404, "Not Found", "{\"error\":\"ticket not found\"}");
        String orderId = request.path().split("/")[2];
        if (buyerTokens != null && !ticketService.ownedBy(orderId, subject))
          return HttpReply.json(
              403, "Forbidden", "{\"error\":\"ticket belongs to another buyer\"}");
        var url = ticketService.downloadUrl(orderId);
        return url.isPresent()
            ? new HttpReply(200, "OK", json.writeValueAsBytes(Map.of("url", url.get().toString())))
            : HttpReply.json(404, "Not Found", "{\"error\":\"ticket not found\"}");
      }
      if (paymentService != null
          && request.method().equals("POST")
          && request.path().equals("/payments")) {
        try {
          var paymentBody = json.readTree(request.body());
          String orderId = paymentBody == null ? "" : paymentBody.path("orderId").asText();
          if (ticketService != null && !ticketService.hasOrder(orderId))
            return HttpReply.json(404, "Not Found", "{\"error\":\"order not found\"}");
          HttpReply reply =
              paymentService.pay(request.headers().get("idempotency-key"), request.body());
          if (ticketService != null && reply.status() == 201) ticketService.issue(orderId);
          return reply;
        } catch (PaymentProviderStub.ChargeNotMadeException e) {
          return HttpReply.json(402, "Payment Required", "{\"error\":\"payment declined\"}");
        } catch (IllegalArgumentException e) {
          return HttpReply.json(
              422, "Unprocessable Content", "{\"error\":\"key reused with different payment\"}");
        } catch (IllegalStateException e) {
          return HttpReply.json(
              503, "Service Unavailable", "{\"error\":\"payment outcome unknown\"}");
        }
      }
      if (payments != null && request.path().startsWith("/payments/")) {
        String orderId = request.headers().getOrDefault("x-order-id", "demo");
        try {
          String result =
              request.method().equals("POST") && request.path().equals("/payments/charge")
                  ? payments.charge(orderId, java.time.Duration.ofSeconds(2))
                  : request.method().equals("GET")
                          && request.path().equals("/payments/charge-status")
                      ? payments.chargeStatus(orderId, java.time.Duration.ofSeconds(2))
                      : null;
          if (result == null)
            return HttpReply.json(404, "Not Found", "{\"error\":\"route not found\"}");
          return HttpReply.json(200, "OK", result);
        } catch (IOException e) {
          return HttpReply.json(
              503, "Service Unavailable", "{\"error\":\"payment provider unavailable\"}");
        }
      }
      if (request.method().equals("POST") && request.path().equals("/orders")) {
        var span =
            GlobalOpenTelemetry.getTracer("stubline.orders").spanBuilder("order.place").startSpan();
        try (var scope = span.makeCurrent()) {
          JsonNode body = json.readTree(request.body());
          if (body == null
              || !body.path("eventId").isTextual()
              || !body.path("seatId").isTextual()
              || body.path("eventId").asText().isBlank()
              || body.path("seatId").asText().isBlank())
            return HttpReply.json(
                400, "Bad Request", "{\"error\":\"eventId and seatId are required\"}");
          String eventId = body.path("eventId").asText(), seatId = body.path("seatId").asText();
          if (!inventory.reserve(eventId, seatId))
            return HttpReply.json(409, "Conflict", "{\"error\":\"seat unavailable\"}");
          Order order = new Order(UUID.randomUUID().toString(), eventId, seatId, Instant.now());
          span.setAttribute("order.id", order.id());
          if (orderStore != null)
            orderStore.save(
                order,
                body.path("buyer").asText("anonymous"),
                body.path("amountMinor").asLong(0),
                body.path("currency").asText("AUD"),
                subject,
                traceparent(Span.current()));
          return new HttpReply(
              201,
              "Created",
              json.writeValueAsBytes(
                  Map.of(
                      "id",
                      order.id(),
                      "eventId",
                      order.eventId(),
                      "seatId",
                      order.seatId(),
                      "createdAt",
                      order.createdAt().toString())));
        } finally {
          span.end();
        }
      }
      return HttpReply.json(404, "Not Found", "{\"error\":\"route not found\"}");
    } catch (io.grpc.StatusRuntimeException e) {
      return switch (e.getStatus().getCode()) {
        case NOT_FOUND -> HttpReply.json(404, "Not Found", "{\"error\":\"seat not found\"}");
        case FAILED_PRECONDITION ->
            HttpReply.json(409, "Conflict", "{\"error\":\"seat unavailable\"}");
        case DEADLINE_EXCEEDED ->
            HttpReply.json(504, "Gateway Timeout", "{\"error\":\"inventory deadline exceeded\"}");
        default ->
            HttpReply.json(503, "Service Unavailable", "{\"error\":\"inventory unavailable\"}");
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return HttpReply.json(503, "Service Unavailable", "{\"error\":\"interrupted\"}");
    } catch (java.sql.SQLException e) {
      return HttpReply.json(503, "Service Unavailable", "{\"error\":\"database unavailable\"}");
    } catch (software.amazon.awssdk.core.exception.SdkException e) {
      return HttpReply.json(
          503, "Service Unavailable", "{\"error\":\"ticket storage unavailable\"}");
    } catch (IOException e) {
      return HttpReply.json(400, "Bad Request", "{\"error\":\"invalid JSON\"}");
    }
  }

  private static String traceparent(Span span) {
    var context = span.getSpanContext();
    return context.isValid()
        ? "00-"
            + context.getTraceId()
            + "-"
            + context.getSpanId()
            + "-"
            + (context.isSampled() ? "01" : "00")
        : null;
  }

  public int peakInFlight() {
    return peakInFlight.get();
  }

  public static TicketingApi sample() {
    return sample(0);
  }

  public static TicketingApi sample(long lookupDelayMillis) {
    var inventory = new InMemoryInventory();
    inventory.add(new Seat("A1", "concert-1", true));
    inventory.add(new Seat("A2", "concert-1", true));
    return new TicketingApi(inventory, lookupDelayMillis);
  }
}
