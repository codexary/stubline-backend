package com.elsworne.stubline.http;

import com.elsworne.stubline.catalog.Event;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transport-independent seat and order routes. */
public final class TicketingApi {
  private final ObjectMapper json = new ObjectMapper();
  private static final List<Event> EVENTS =
      List.of(new Event("concert-1", "Concert 1", Instant.parse("2027-01-01T20:00:00Z")));
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

  private java.util.Set<String> corsOrigins = java.util.Set.of();

  public void configureCors(String origins) {
    corsOrigins =
        origins == null || origins.isBlank()
            ? java.util.Set.of()
            : java.util.Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank() && !origin.equals("*"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public HttpReply handle(HttpRequest request) {
    if (request.path().startsWith("/diagnostics/"))
      return ProblemDetails.of(404, "Not Found", "route not found", request.path());
    return route(request);
  }

  HttpReply handleAdmin(HttpRequest request) {
    return route(request);
  }

  private HttpReply route(HttpRequest request) {
    String origin = request.header("origin");
    boolean allowedOrigin = origin != null && corsOrigins.contains(origin);
    if (request.method().equals("OPTIONS")
        && request.header("access-control-request-method") != null) {
      String method = request.header("access-control-request-method");
      String requestedHeaders = request.header("access-control-request-headers");
      boolean validHeaders =
          requestedHeaders == null
              || java.util.Arrays.stream(requestedHeaders.split(","))
                  .map(String::trim)
                  .map(String::toLowerCase)
                  .allMatch(
                      header ->
                          java.util.Set.of("authorization", "content-type", "idempotency-key")
                              .contains(header));
      if (!allowedOrigin
          || !java.util.Set.of("GET", "POST", "HEAD").contains(method)
          || !validHeaders)
        return secure(
            ProblemDetails.of(
                403, "Forbidden", "CORS origin, method, or headers not allowed", request.path()),
            request,
            false);
      var headers = new java.util.HashMap<String, String>();
      headers.put("Access-Control-Allow-Methods", "GET, POST, HEAD");
      headers.put("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key");
      headers.put("Access-Control-Max-Age", "600");
      return secure(new HttpReply(204, "No Content", new byte[0], headers), request, true);
    }
    return secure(
        ProblemDetails.normalize(handleRoute(request), request.path()), request, allowedOrigin);
  }

  private HttpReply secure(HttpReply reply, HttpRequest request, boolean allowedOrigin) {
    var headers = new java.util.HashMap<>(reply.headers());
    headers.put("Strict-Transport-Security", "max-age=31536000");
    headers.put("X-Content-Type-Options", "nosniff");
    if (request.header("authorization") != null) headers.put("Cache-Control", "no-store");
    if (allowedOrigin) {
      headers.put("Access-Control-Allow-Origin", request.header("origin"));
      headers.put("Access-Control-Allow-Credentials", "true");
      headers.put("Vary", "Origin");
    }
    return new HttpReply(reply.status(), reply.reason(), reply.body(), headers);
  }

  private HttpReply handleRoute(HttpRequest request) {
    try {
      String subject = null;
      boolean protectedRoute =
          request.method().equals("POST")
                  && (request.path().equals("/orders") || request.path().equals("/payments"))
              || request.method().equals("GET")
                  && request.path().matches("/orders/[^/]+(?:/ticket)?");
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
          && (request.path().equals("/health") || request.path().equals("/health/live")))
        return HttpReply.json(200, "OK", "{\"status\":\"ok\"}");
      if ((request.method().equals("GET") || request.method().equals("HEAD"))
          && request.path().equals("/health/ready"))
        return inventory.ready() && (paymentService == null || paymentService.ready())
            ? HttpReply.json(200, "OK", "{\"status\":\"ready\"}")
            : ProblemDetails.of(
                503, "Service Unavailable", "inventory unavailable", request.path());
      if (request.method().equals("GET") && request.path().matches("/events/[^/]+/seats")) {
        String eventId = request.path().split("/")[2];
        int now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
        try {
          if (lookupDelayMillis > 0) Thread.sleep(lookupDelayMillis);
        } finally {
          inFlight.decrementAndGet();
        }
        return seatPage(request, eventId);
      }
      if (request.method().equals("GET") && request.path().equals("/events"))
        return eventPage(request);
      if (request.method().equals("GET") && request.path().equals("/diagnostics/dns")) {
        String query = request.query();
        if (query == null || !query.startsWith("name="))
          return ProblemDetails.of(400, "Bad Request", "name is required", request.path());
        String name =
            java.net.URLDecoder.decode(query.substring(5), java.nio.charset.StandardCharsets.UTF_8);
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
          return ProblemDetails.of(404, "Not Found", "ticket not found", request.path());
        String orderId = request.path().split("/")[2];
        if (buyerTokens != null && !ticketService.ownedBy(orderId, subject))
          return HttpReply.json(
              403, "Forbidden", "{\"error\":\"ticket belongs to another buyer\"}");
        var url = ticketService.downloadUrl(orderId);
        return url.isPresent()
            ? new HttpReply(200, "OK", json.writeValueAsBytes(Map.of("url", url.get().toString())))
            : ProblemDetails.of(404, "Not Found", "ticket not found", request.path());
      }
      if (request.method().equals("GET") && request.path().matches("/orders/[^/]+")) {
        if (orderStore == null && ticketService == null)
          return ProblemDetails.of(
              503, "Service Unavailable", "order store unavailable", request.path());
        String orderId = request.path().substring("/orders/".length());
        if (buyerTokens != null
            && !(orderStore != null
                ? orderStore.ownedBy(orderId, subject)
                : ticketService.ownedBy(orderId, subject)))
          return ProblemDetails.of(404, "Not Found", "order not found", request.path());
        var order =
            orderStore != null ? orderStore.find(orderId) : ticketService.findOrder(orderId);
        return order.isPresent()
            ? new HttpReply(
                200,
                "OK",
                json.writeValueAsBytes(
                    Map.of(
                        "id",
                        order.get().id(),
                        "eventId",
                        order.get().eventId(),
                        "seatId",
                        order.get().seatId(),
                        "createdAt",
                        order.get().createdAt().toString())))
            : ProblemDetails.of(404, "Not Found", "order not found", request.path());
      }
      if (paymentService != null
          && request.method().equals("POST")
          && request.path().equals("/payments")) {
        try {
          var paymentBody = json.readTree(request.body());
          String orderId = paymentBody == null ? "" : paymentBody.path("orderId").asText();
          if (buyerTokens != null && ticketService == null && orderStore == null)
            return ProblemDetails.of(
                503, "Service Unavailable", "order ownership store unavailable", request.path());
          boolean orderExists =
              ticketService != null
                  ? ticketService.hasOrder(orderId)
                  : orderStore == null || orderStore.find(orderId).isPresent();
          if (!orderExists)
            return ProblemDetails.of(404, "Not Found", "order not found", request.path());
          if (buyerTokens != null
              && !(ticketService != null
                  ? ticketService.ownedBy(orderId, subject)
                  : orderStore.ownedBy(orderId, subject)))
            return ProblemDetails.of(
                403, "Forbidden", "order belongs to another buyer", request.path());
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
            return ProblemDetails.of(404, "Not Found", "route not found", request.path());
          return HttpReply.json(200, "OK", result);
        } catch (PaymentProviderClient.ProviderRejectedException e) {
          int status = e.status() == 402 ? 402 : 422;
          return ProblemDetails.of(
              status,
              status == 402 ? "Payment Required" : "Unprocessable Content",
              status == 402 ? "payment declined" : "provider rejected payment request",
              request.path());
        } catch (IOException e) {
          return ProblemDetails.of(
              503, "Service Unavailable", "payment provider unavailable", request.path());
        }
      }
      if (request.method().equals("POST") && request.path().equals("/orders")) {
        var span =
            GlobalOpenTelemetry.getTracer("stubline.orders").spanBuilder("order.place").startSpan();
        try (var scope = span.makeCurrent()) {
          JsonNode body = json.readTree(request.body());
          if (body == null || !body.isObject())
            return ProblemDetails.of(400, "Bad Request", "Expected a JSON object", request.path());
          for (String field : new String[] {"eventId", "seatId"}) {
            if (!body.path(field).isTextual() || body.path(field).asText().isBlank())
              return ProblemDetails.of(
                  400,
                  "Bad Request",
                  field + " must be a non-empty string",
                  request.path(),
                  Map.of("field", field));
          }
          String eventId = body.path("eventId").asText(), seatId = body.path("seatId").asText();
          if (!inventory.reserve(eventId, seatId))
            return ProblemDetails.of(
                409, "Conflict", "seat unavailable", request.path(), Map.of("seatId", seatId));
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
      return ProblemDetails.of(404, "Not Found", "route not found", request.path());
    } catch (io.grpc.StatusRuntimeException e) {
      return switch (e.getStatus().getCode()) {
        case NOT_FOUND -> ProblemDetails.of(404, "Not Found", "seat not found", request.path());
        case FAILED_PRECONDITION ->
            ProblemDetails.of(409, "Conflict", "seat unavailable", request.path());
        case DEADLINE_EXCEEDED ->
            ProblemDetails.of(
                504, "Gateway Timeout", "inventory deadline exceeded", request.path());
        default ->
            ProblemDetails.of(503, "Service Unavailable", "inventory unavailable", request.path());
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ProblemDetails.of(503, "Service Unavailable", "interrupted", request.path());
    } catch (java.sql.SQLException e) {
      return ProblemDetails.of(503, "Service Unavailable", "database unavailable", request.path());
    } catch (software.amazon.awssdk.core.exception.SdkException e) {
      return ProblemDetails.of(
          503, "Service Unavailable", "ticket storage unavailable", request.path());
    } catch (IllegalArgumentException e) {
      return ProblemDetails.of(400, "Bad Request", e.getMessage(), request.path());
    } catch (IOException e) {
      return ProblemDetails.of(400, "Bad Request", "Malformed JSON", request.path());
    }
  }

  private HttpReply seatPage(HttpRequest request, String eventId)
      throws IOException, java.sql.SQLException {
    int limit = 20;
    String cursor = null;
    if (request.query() != null && !request.query().isBlank()) {
      for (String part : request.query().split("&")) {
        String[] pair = part.split("=", 2);
        String key = java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8);
        String value =
            pair.length == 2
                ? java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8)
                : "";
        if (key.equals("limit")) limit = Integer.parseInt(value);
        else if (key.equals("cursor")) cursor = value;
        else throw new IllegalArgumentException("Unknown query parameter: " + key);
      }
    }
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("limit must be between 1 and 100");
    String after = null;
    if (cursor != null) {
      String decoded =
          new String(
              java.util.Base64.getUrlDecoder().decode(cursor),
              java.nio.charset.StandardCharsets.UTF_8);
      String prefix = eventId + ":";
      if (!decoded.startsWith(prefix) || decoded.length() == prefix.length())
        throw new IllegalArgumentException("Invalid cursor");
      after = decoded.substring(prefix.length());
    }
    var all =
        inventory.seatsFor(eventId).stream()
            .sorted(java.util.Comparator.comparing(Seat::id))
            .toList();
    var page = new java.util.ArrayList<Seat>();
    for (Seat seat : all) {
      if (after != null && seat.id().compareTo(after) <= 0) continue;
      page.add(seat);
      if (page.size() > limit) break;
    }
    String next = null;
    if (page.size() > limit) {
      page.remove(page.size() - 1);
      next =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (eventId + ":" + page.getLast().id())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("seats", page);
    body.put("next", next);
    return new HttpReply(200, "OK", json.writeValueAsBytes(body));
  }

  private HttpReply eventPage(HttpRequest request) throws IOException {
    int limit = 20;
    String after = null;
    if (request.query() != null && !request.query().isBlank()) {
      for (String part : request.query().split("&")) {
        String[] pair = part.split("=", 2);
        String key = java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8);
        String value =
            pair.length == 2
                ? java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8)
                : "";
        if (key.equals("limit")) limit = Integer.parseInt(value);
        else if (key.equals("cursor")) {
          String decoded =
              new String(
                  java.util.Base64.getUrlDecoder().decode(value),
                  java.nio.charset.StandardCharsets.UTF_8);
          if (!decoded.startsWith("events:") || decoded.length() == "events:".length())
            throw new IllegalArgumentException("Invalid cursor");
          after = decoded.substring("events:".length());
          if (EVENTS.stream()
              .map(Event::id)
              .noneMatch(decoded.substring("events:".length())::equals))
            throw new IllegalArgumentException("Invalid cursor");
        } else throw new IllegalArgumentException("Unknown query parameter: " + key);
      }
    }
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("limit must be between 1 and 100");
    var page = new java.util.ArrayList<Event>();
    for (Event event : EVENTS) {
      if (after != null && event.id().compareTo(after) <= 0) continue;
      page.add(event);
      if (page.size() > limit) break;
    }
    String next = null;
    if (page.size() > limit) {
      page.remove(page.size() - 1);
      next =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  ("events:" + page.getLast().id())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put(
        "events",
        page.stream()
            .map(
                event ->
                    Map.of(
                        "id",
                        event.id(),
                        "title",
                        event.title(),
                        "startsAt",
                        event.startsAt().toString()))
            .toList());
    body.put("next", next);
    return new HttpReply(200, "OK", json.writeValueAsBytes(body));
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
