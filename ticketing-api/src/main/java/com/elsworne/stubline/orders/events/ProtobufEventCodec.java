package com.elsworne.stubline.orders.events;

import com.elsworne.stubline.orders.events.proto.OrderPlacedSchema;
import java.io.IOException;
import java.time.Instant;

/** Protobuf wire format generated from the shared order_placed.proto. */
public final class ProtobufEventCodec implements EventCodec {
  @Override
  public byte[] encode(OrderPlaced event) {
    var builder =
        OrderPlacedSchema.OrderPlaced.newBuilder()
            .setOrderId(event.orderId())
            .setEventId(event.concertId())
            .setSeatId(event.seatId())
            .setBuyer(event.buyer())
            .setAmountMinor(event.amountMinor())
            .setCurrency(event.currency())
            .setOccurredAt(event.occurredAt().toString())
            .setMessageId(event.eventId());
    if (event.buyerNote() != null) builder.setBuyerNote(event.buyerNote());
    return builder.build().toByteArray();
  }

  @Override
  public OrderPlaced decode(byte[] bytes) throws IOException {
    var wire = OrderPlacedSchema.OrderPlaced.parseFrom(bytes);
    if (wire.getOrderId().isEmpty()
        || wire.getEventId().isEmpty()
        || wire.getSeatId().isEmpty()
        || wire.getBuyer().isEmpty()
        || wire.getCurrency().isEmpty()
        || wire.getOccurredAt().isEmpty()
        || !wire.hasAmountMinor()
        || wire.getMessageId().isEmpty()) throw new IOException("Missing required event field");
    return new OrderPlaced(
        wire.getOrderId(),
        wire.getEventId(),
        wire.getSeatId(),
        wire.getBuyer(),
        wire.getAmountMinor(),
        wire.getCurrency(),
        Instant.parse(wire.getOccurredAt()),
        wire.hasBuyerNote() ? wire.getBuyerNote() : null,
        wire.getMessageId());
  }
}
