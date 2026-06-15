package com.elsworne.stubline.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Provider-owned keyed charge history, independent of the ticketing API store. */
public final class ProviderCharges {
  private final Map<String, Charge> charges = new HashMap<>();
  private final ObjectMapper json = new ObjectMapper();
  private boolean declineNext;
  private final long replyDelayMillis;

  public ProviderCharges() {
    this(0);
  }

  public ProviderCharges(long replyDelayMillis) {
    this.replyDelayMillis = replyDelayMillis;
  }

  public synchronized void declineNext() {
    declineNext = true;
  }

  public synchronized int count() {
    return charges.size();
  }

  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!exchange.getRequestMethod().equals("POST")) {
        reply(exchange, 405, "{}");
        return;
      }
      String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      if (key == null || key.isBlank()) {
        reply(exchange, 400, "{\"error\":\"missing key\"}");
        return;
      }
      var body = json.readTree(exchange.getRequestBody().readNBytes(1_048_577));
      if (body == null
          || !body.path("orderId").isTextual()
          || !body.path("amountCents").isIntegralNumber()
          || body.path("amountCents").asLong() <= 0) {
        reply(exchange, 400, "{\"error\":\"invalid payment\"}");
        return;
      }
      String orderId = body.path("orderId").asText();
      long amount = body.path("amountCents").asLong();
      String result;
      synchronized (this) {
        Charge existing = charges.get(key);
        if (existing != null && (!existing.orderId.equals(orderId) || existing.amount != amount)) {
          reply(exchange, 422, "{\"error\":\"key reused\"}");
          return;
        }
        if (declineNext && existing == null) {
          declineNext = false;
          reply(exchange, 402, "{\"status\":\"declined\"}");
          return;
        }
        if (existing == null) {
          existing = new Charge(orderId, amount, "ch_" + (charges.size() + 1));
          charges.put(key, existing);
        }
        result = "{\"chargeId\":\"" + existing.id + "\"}";
      }
      if (replyDelayMillis > 0) {
        try {
          Thread.sleep(replyDelayMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      reply(exchange, 201, result);
    }
  }

  private static void reply(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private record Charge(String orderId, long amount, String id) {}
}
