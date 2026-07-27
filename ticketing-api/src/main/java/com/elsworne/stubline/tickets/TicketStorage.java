package com.elsworne.stubline.tickets;

import java.net.URI;
import java.time.Duration;

/** Stores rendered tickets and grants short-lived reads. */
public interface TicketStorage extends AutoCloseable {
  void put(String key, byte[] pdf);

  URI presignGet(String key, Duration lifetime);

  @Override
  default void close() {}
}
