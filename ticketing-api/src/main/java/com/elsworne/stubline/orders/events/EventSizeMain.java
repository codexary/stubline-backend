package com.elsworne.stubline.orders.events;

import java.time.Instant;
import java.util.HexFormat;

/** Prints the size and field-tagged Protobuf bytes of one sample event. */
public final class EventSizeMain {
  private EventSizeMain() {}

  public static void main(String[] args) throws Exception {
    var event =
        new OrderPlaced(
            "order-1",
            "concert-1",
            "A1",
            "buyer@example.com",
            12900,
            "AUD",
            Instant.parse("2026-10-29T00:00:00Z"),
            null);
    byte[] json = new JsonEventCodec().encode(event);
    byte[] proto = new ProtobufEventCodec().encode(event);
    System.out.printf("json=%d bytes protobuf=%d bytes%n", json.length, proto.length);
    System.out.println(HexFormat.of().formatHex(proto));
    System.out.println(
        "tags: 0a=orderId(1), 12=eventId(2), 1a=seatId(3), 22=buyer(4), 28=amountMinor(5), 32=currency(6), 3a=occurredAt(7), 42=buyerNote(8, if present)");
  }
}
