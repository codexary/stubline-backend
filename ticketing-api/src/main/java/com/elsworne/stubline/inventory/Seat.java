package com.elsworne.stubline.inventory;

import java.util.Objects;

/** A seat and its current availability for an event. */
public record Seat(String id, String eventId, boolean available) {
  public Seat {
    Objects.requireNonNull(id);
    Objects.requireNonNull(eventId);
    if (id.isBlank() || eventId.isBlank())
      throw new IllegalArgumentException("Seat and event ids are required");
  }
}
