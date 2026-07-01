package com.elsworne.stubline.payments;

import com.elsworne.stubline.http.HttpReply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Applies request validation and idempotency to payment attempts. */
public final class PaymentService {
  private final IdempotencyStore store;
  private final ChargeProvider provider;
  private final Clock clock;
  private final ObjectMapper json = new ObjectMapper();

  public PaymentService(IdempotencyStore store, ChargeProvider provider, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.provider = Objects.requireNonNull(provider);
    this.clock = Objects.requireNonNull(clock);
  }

  public boolean ready() {
    return store.ready();
  }

  public HttpReply pay(String key, byte[] body) {
    if (key == null || key.isBlank() || key.length() > 255)
      return HttpReply.json(
          400, "Bad Request", "{\"error\":\"Idempotency-Key is required (max 255 characters)\"}");
    if (body.length > 1_048_576)
      return HttpReply.json(413, "Content Too Large", "{\"error\":\"request too large\"}");
    JsonNode request;
    try {
      request = json.readTree(body);
    } catch (IOException e) {
      return HttpReply.json(400, "Bad Request", "{\"error\":\"invalid JSON\"}");
    }
    if (request == null
        || !request.path("orderId").isTextual()
        || request.path("orderId").asText().isBlank()
        || !request.path("amountCents").isIntegralNumber()
        || !request.path("amountCents").canConvertToLong()
        || request.path("amountCents").asLong() <= 0) {
      return HttpReply.json(
          400, "Bad Request", "{\"error\":\"orderId and positive amountCents are required\"}");
    }
    String hash = sha256(body);
    IdempotencyStore.Decision decision =
        store.begin(key, hash, clock.instant().plus(Duration.ofHours(24)));
    if (decision instanceof IdempotencyStore.Replay replay) return replay.reply();
    if (decision instanceof IdempotencyStore.Mismatch)
      return HttpReply.json(
          422, "Unprocessable Content", "{\"error\":\"key reused with different body\"}");
    if (decision instanceof IdempotencyStore.InFlight)
      return HttpReply.json(409, "Conflict", "{\"error\":\"request still in flight\"}");
    String chargeId;
    try {
      chargeId =
          provider.charge(
              key, request.path("orderId").asText(), request.path("amountCents").asLong());
    } catch (PaymentProviderStub.ChargeNotMadeException | IllegalArgumentException e) {
      // Only a definitive pre-charge failure releases the claim. An unknown outcome or a
      // failure saving a successful charge must retain it for reconciliation, not blind retry.
      store.release(key, hash);
      throw e;
    }
    try {
      HttpReply reply =
          new HttpReply(
              201,
              "Created",
              json.writeValueAsBytes(
                  Map.of(
                      "chargeId",
                      chargeId,
                      "orderId",
                      request.path("orderId").asText(),
                      "amountCents",
                      request.path("amountCents").asLong())));
      store.complete(key, hash, reply);
      return reply;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot encode payment", e);
    }
  }

  private static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
