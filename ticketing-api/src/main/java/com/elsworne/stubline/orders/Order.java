package com.elsworne.stubline.orders;

import java.time.Instant;
import java.util.Objects;

/** A reservation of one seat for a customer. */
public record Order(String id, String eventId, String seatId, Instant createdAt) {
  public Order {
    Objects.requireNonNull(id);
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(seatId);
    Objects.requireNonNull(createdAt);
  }
}
