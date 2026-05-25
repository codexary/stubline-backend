package com.elsworne.stubline.orders.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;

/** Jackson JSON wire format with explicit forward-compatible unknown-field handling. */
public final class JsonEventCodec implements EventCodec {
  private final ObjectMapper json =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Override
  public byte[] encode(OrderPlaced event) throws IOException {
    return json.writeValueAsBytes(
        new Wire(
            event.orderId(),
            event.eventId(),
            event.seatId(),
            event.buyer(),
            event.amountMinor(),
            event.currency(),
            event.occurredAt().toString(),
            event.buyerNote()));
  }

  @Override
  public OrderPlaced decode(byte[] bytes) throws IOException {
    Wire wire = json.readValue(bytes, Wire.class);
    if (wire.orderId() == null
        || wire.eventId() == null
        || wire.seatId() == null
        || wire.buyer() == null
        || wire.currency() == null
        || wire.occurredAt() == null
        || wire.amountMinor() == null
        || wire.orderId().isEmpty()
        || wire.eventId().isEmpty()
        || wire.seatId().isEmpty()
        || wire.buyer().isEmpty()
        || wire.currency().isEmpty()
        || wire.occurredAt().isEmpty()) throw new IOException("Missing required event field");
    return new OrderPlaced(
        wire.orderId(),
        wire.eventId(),
        wire.seatId(),
        wire.buyer(),
        wire.amountMinor(),
        wire.currency(),
        Instant.parse(wire.occurredAt()),
        wire.buyerNote());
  }

  private record Wire(
      String orderId,
      String eventId,
      String seatId,
      String buyer,
      Long amountMinor,
      String currency,
      String occurredAt,
      String buyerNote) {}
}
