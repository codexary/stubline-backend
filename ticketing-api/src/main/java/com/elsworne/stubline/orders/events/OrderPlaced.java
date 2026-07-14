package com.elsworne.stubline.orders.events;

import java.time.Instant;

/** The durable order event shared by future producers and consumers. */
public record OrderPlaced(
    String orderId,
    String concertId,
    String seatId,
    String buyer,
    long amountMinor,
    String currency,
    Instant occurredAt,
    String buyerNote,
    String eventId) {
  public OrderPlaced(
      String orderId,
      String concertId,
      String seatId,
      String buyer,
      long amountMinor,
      String currency,
      Instant occurredAt,
      String buyerNote) {
    this(orderId, concertId, seatId, buyer, amountMinor, currency, occurredAt, buyerNote, orderId);
  }
}
