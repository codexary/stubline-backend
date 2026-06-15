package com.elsworne.stubline.payments;

import com.elsworne.stubline.http.HttpReply;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Keeps payment request decisions in memory. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {
  private final Clock clock;
  private final Map<String, Entry> entries = new HashMap<>();

  public InMemoryIdempotencyStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized Decision begin(String key, String hash, Instant expiresAt) {
    Entry entry = entries.get(key);
    if (entry != null && entry.reply != null && !entry.expiresAt.isAfter(clock.instant())) {
      entries.remove(key);
      entry = null;
    }
    if (entry == null) {
      entries.put(key, new Entry(hash, expiresAt));
      return new First();
    }
    if (!entry.hash.equals(hash)) return new Mismatch();
    return entry.reply == null ? new InFlight() : new Replay(entry.reply);
  }

  @Override
  public synchronized void complete(String key, String hash, HttpReply reply) {
    Entry entry = entries.get(key);
    if (entry == null || !entry.hash.equals(hash) || entry.reply != null)
      throw new IllegalStateException("No matching in-flight request");
    entry.reply = reply;
  }

  @Override
  public synchronized void release(String key, String hash) {
    Entry entry = entries.get(key);
    if (entry != null && entry.hash.equals(hash) && entry.reply == null) {
      entries.remove(key);
    }
  }

  private static final class Entry {
    final String hash;
    final Instant expiresAt;
    HttpReply reply;

    Entry(String hash, Instant expiresAt) {
      this.hash = hash;
      this.expiresAt = expiresAt;
    }
  }
}
