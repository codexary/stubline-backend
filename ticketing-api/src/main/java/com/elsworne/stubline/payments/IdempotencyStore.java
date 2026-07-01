package com.elsworne.stubline.payments;

import com.elsworne.stubline.http.HttpReply;
import java.time.Instant;

/** Claims keys and stores completed payment replies. */
public interface IdempotencyStore {
  default boolean ready() {
    return true;
  }

  Decision begin(String key, String bodyHash, Instant expiresAt);

  void complete(String key, String bodyHash, HttpReply reply);

  /** Releases only a matching in-flight attempt known to have moved no money. */
  void release(String key, String bodyHash);

  /** Result of attempting to claim a payment key. */
  sealed interface Decision permits First, Replay, InFlight, Mismatch {}

  /** The caller owns a new in-flight claim. */
  record First() implements Decision {}

  /** A completed response to return without charging again. */
  record Replay(HttpReply reply) implements Decision {}

  /** An earlier matching attempt has not completed. */
  record InFlight() implements Decision {}

  /** The key belongs to a different request body. */
  record Mismatch() implements Decision {}
}
