package com.elsworne.stubline.http;

import com.elsworne.stubline.inventory.InMemoryInventory;
import com.elsworne.stubline.inventory.Seat;
import com.elsworne.stubline.inventory.SeatInventory;
import com.elsworne.stubline.orders.Order;
import com.elsworne.stubline.payments.PaymentProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Transport-independent seat and order routes. */
public final class TicketingApi {
  private final ObjectMapper json = new ObjectMapper();
  private final SeatInventory inventory;
  private final PaymentProviderClient payments;
  private final long lookupDelayMillis;
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
    if (lookupDelayMillis < 0) throw new IllegalArgumentException("Negative lookup delay");
    this.inventory = inventory;
    this.lookupDelayMillis = lookupDelayMillis;
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
    try {
      if ((request.method().equals("GET") || request.method().equals("HEAD"))
          && (request.path().equals("/health") || request.path().equals("/health/live")))
        return HttpReply.json(200, "OK", "{\"status\":\"ok\"}");
      if ((request.method().equals("GET") || request.method().equals("HEAD"))
          && request.path().equals("/health/ready"))
        return inventory.ready()
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
      }
      return ProblemDetails.of(404, "Not Found", "route not found", request.path());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ProblemDetails.of(503, "Service Unavailable", "interrupted", request.path());
    } catch (java.sql.SQLException e) {
      return ProblemDetails.of(503, "Service Unavailable", "inventory unavailable", request.path());
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
