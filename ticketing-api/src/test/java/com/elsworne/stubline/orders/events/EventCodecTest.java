package com.elsworne.stubline.orders.events;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.orders.events.proto.OrderPlacedSchema;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventCodecTest {
  private final OrderPlaced event =
      new OrderPlaced(
          "o1", "e1", "A1", "buyer", 1234, "AUD", Instant.parse("2026-10-29T00:00:00Z"), null);

  @Test
  void bothFormatsRoundTrip() throws Exception {
    for (EventCodec codec : new EventCodec[] {new JsonEventCodec(), new ProtobufEventCodec()})
      assertEquals(event, codec.decode(codec.encode(event)));
  }

  @Test
  void oldProtobufConsumerReadsNewOptionalField() throws Exception {
    var newer =
        new OrderPlaced(
            event.orderId(),
            event.concertId(),
            event.seatId(),
            event.buyer(),
            event.amountMinor(),
            event.currency(),
            event.occurredAt(),
            "aisle");
    var old =
        OrderPlacedSchema.OrderPlaced.getDescriptor().toProto().toBuilder()
            .removeField(8)
            .removeField(7)
            .removeOneofDecl(1)
            .build();
    var file =
        DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("old_order.proto")
            .setSyntax("proto3")
            .addMessageType(old)
            .build();
    var descriptor =
        Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0])
            .getMessageTypes()
            .getFirst();
    var parsed = DynamicMessage.parseFrom(descriptor, new ProtobufEventCodec().encode(newer));
    assertEquals("o1", parsed.getField(descriptor.findFieldByNumber(1)));
    assertEquals(2, parsed.getUnknownFields().asMap().size());
  }

  @Test
  void removedProtobufNumberIsReserved() {
    var descriptor = OrderPlacedSchema.OrderPlaced.getDescriptor().toProto();
    assertEquals(9, descriptor.getReservedRange(0).getStart());
    assertEquals(10, descriptor.getReservedRange(0).getEnd());
    assertEquals("legacy_source", descriptor.getReservedName(0));
  }

  @Test
  void oldJsonConsumerIgnoresAddedFieldDeliberately() throws Exception {
    String json = new String(new JsonEventCodec().encode(event), StandardCharsets.UTF_8);
    assertEquals(
        event,
        new JsonEventCodec()
            .decode(json.replace("{", "{\"newField\":true,").getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void renamingJsonFieldBreaksOldConsumer() throws Exception {
    String json = new String(new JsonEventCodec().encode(event), StandardCharsets.UTF_8);
    assertThrows(
        IOException.class,
        () ->
            new JsonEventCodec()
                .decode(
                    json.replace("\"buyer\"", "\"purchaser\"").getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void jsonRejectsMissingAmountMinor() {
    String json =
        "{\"orderId\":\"o1\",\"eventId\":\"e1\",\"seatId\":\"A1\",\"buyer\":\"buyer\",\"currency\":\"AUD\",\"occurredAt\":\"2026-10-29T00:00:00Z\"}";
    assertThrows(
        IOException.class,
        () -> new JsonEventCodec().decode(json.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void protobufRejectsMissingBusinessFields() {
    byte[] bytes =
        OrderPlacedSchema.OrderPlaced.newBuilder().setOrderId("o1").build().toByteArray();
    assertThrows(IOException.class, () -> new ProtobufEventCodec().decode(bytes));
  }

  @Test
  void explicitZeroAmountRoundTrips() throws Exception {
    var free =
        new OrderPlaced(
            "o1", "e1", "A1", "buyer", 0, "AUD", Instant.parse("2026-10-29T00:00:00Z"), null);
    for (EventCodec codec : new EventCodec[] {new JsonEventCodec(), new ProtobufEventCodec()})
      assertEquals(free, codec.decode(codec.encode(free)));
  }

  @Test
  void protobufRejectsMissingAmountMinor() {
    byte[] bytes =
        OrderPlacedSchema.OrderPlaced.newBuilder()
            .setOrderId("o1")
            .setEventId("e1")
            .setSeatId("A1")
            .setBuyer("buyer")
            .setCurrency("AUD")
            .setOccurredAt("2026-10-29T00:00:00Z")
            .build()
            .toByteArray();
    assertThrows(IOException.class, () -> new ProtobufEventCodec().decode(bytes));
  }
}
