package com.elsworne.stubline.http;

import com.elsworne.stubline.inventory.InMemoryInventory;
import com.elsworne.stubline.inventory.Seat;
import com.elsworne.stubline.orders.Order;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Transport-independent seat and order routes. */
public final class TicketingApi {
  private final ObjectMapper json = new ObjectMapper();
  private final InMemoryInventory inventory;

  public TicketingApi(InMemoryInventory inventory) {
    this.inventory = inventory;
  }

  public HttpReply handle(HttpRequest request) {
    try {
      if (request.method().equals("GET") && request.path().matches("/events/[^/]+/seats")) {
        String eventId = request.path().split("/")[2];
        return new HttpReply(200, "OK", json.writeValueAsBytes(inventory.seatsFor(eventId)));
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
    } catch (IOException e) {
      return ProblemDetails.of(400, "Bad Request", "Malformed JSON", request.path());
    }
  }

  public static TicketingApi sample() {
    var inventory = new InMemoryInventory();
    inventory.add(new Seat("A1", "concert-1", true));
    inventory.add(new Seat("A2", "concert-1", true));
    return new TicketingApi(inventory);
  }
}
