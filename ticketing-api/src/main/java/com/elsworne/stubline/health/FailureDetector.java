package com.elsworne.stubline.health;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Timeout-based membership view; suspicion is not proof of process failure. */
public final class FailureDetector {
  public enum Status {
    ALIVE,
    SUSPECTED
  }

  public record Member(
      String instanceId, long sequence, long senderTimestampMillis, Status status) {}

  private record Observation(long sequence, long senderTimestampMillis, long receivedAtNanos) {}

  private final long timeoutNanos;
  private final LongSupplier monotonicClock;
  private final Map<String, Observation> observations = new LinkedHashMap<>();

  public FailureDetector(Duration timeout, LongSupplier monotonicClock) {
    if (timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("Timeout must be positive");
    this.timeoutNanos = timeout.toNanos();
    this.monotonicClock = monotonicClock;
  }

  public synchronized boolean observe(Heartbeat heartbeat) {
    var old = observations.get(heartbeat.instanceId());
    if (old != null && heartbeat.sequence() <= old.sequence()) return false;
    observations.put(
        heartbeat.instanceId(),
        new Observation(
            heartbeat.sequence(), heartbeat.timestampMillis(), monotonicClock.getAsLong()));
    return true;
  }

  public synchronized Map<String, Member> membership() {
    long now = monotonicClock.getAsLong();
    Map<String, Member> result = new LinkedHashMap<>();
    observations.forEach(
        (id, seen) ->
            result.put(
                id,
                new Member(
                    id,
                    seen.sequence(),
                    seen.senderTimestampMillis(),
                    now - seen.receivedAtNanos() >= timeoutNanos
                        ? Status.SUSPECTED
                        : Status.ALIVE)));
    return Map.copyOf(result);
  }
}
