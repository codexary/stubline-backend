package com.elsworne.stubline.orders.events;

import java.time.Instant;

/** The durable order event shared by future producers and consumers. */
public record OrderPlaced(
    String orderId,
    String eventId,
    String seatId,
    String buyer,
    long amountMinor,
    String currency,
    Instant occurredAt,
    String buyerNote) {}
