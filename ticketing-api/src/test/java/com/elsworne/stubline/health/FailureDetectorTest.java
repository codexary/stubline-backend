package com.elsworne.stubline.health;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FailureDetectorTest {
  @Test
  void lostReorderedAndDuplicatePacketsDoNotRefreshTheTimeout() throws IOException {
    var time = new AtomicLong();
    var detector = new FailureDetector(Duration.ofMillis(500), time::get);
    var first = new Heartbeat("api-a", 1, 1000);
    assertEquals(first, Heartbeat.decode(first.encode(), first.encode().length));
    assertTrue(detector.observe(first));
    time.set(200_000_000);
    assertFalse(detector.observe(first));
    assertFalse(detector.observe(new Heartbeat("api-a", 0, 999)));
    // Sequence 2 was dropped on purpose. Sequence 3 still establishes liveness.
    assertTrue(detector.observe(new Heartbeat("api-a", 3, 1300)));
    time.set(699_000_000);
    assertEquals(FailureDetector.Status.ALIVE, detector.membership().get("api-a").status());
    time.set(700_000_000);
    assertEquals(FailureDetector.Status.SUSPECTED, detector.membership().get("api-a").status());
    assertEquals(3, detector.membership().get("api-a").sequence());
  }

  @Test
  void aStoppedOrSlowInstanceIsOnlySuspectedAndCanRecover() {
    var time = new AtomicLong();
    var detector = new FailureDetector(Duration.ofMillis(500), time::get);
    detector.observe(new Heartbeat("stopped", 1, 10));
    detector.observe(new Heartbeat("slow", 1, 10));
    time.set(500_000_000);
    assertEquals(FailureDetector.Status.SUSPECTED, detector.membership().get("stopped").status());
    assertEquals(FailureDetector.Status.SUSPECTED, detector.membership().get("slow").status());
    detector.observe(new Heartbeat("slow", 2, 20));
    assertEquals(FailureDetector.Status.ALIVE, detector.membership().get("slow").status());
    assertEquals(FailureDetector.Status.SUSPECTED, detector.membership().get("stopped").status());
  }
}
