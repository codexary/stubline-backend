package com.elsworne.stubline.catalog;

import java.time.Instant;
import java.util.Objects;

/** An event whose seats can be offered for sale. */
public record Event(String id, String title, Instant startsAt) {
  public Event {
    Objects.requireNonNull(id);
    Objects.requireNonNull(title);
    Objects.requireNonNull(startsAt);
    if (id.isBlank() || title.isBlank())
      throw new IllegalArgumentException("Event id and title are required");
  }
}
