package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TicketingShutdownTest {
  @Test
  void waitsForCurrentRelayBatchBeforeClosingDependencies() throws Exception {
    List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var worker = Executors.newSingleThreadExecutor();
    worker.submit(
        () -> {
          entered.countDown();
          try {
            release.await();
            steps.add("batch committed");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    entered.await();
    var shutdown =
        new TicketingShutdown(
            () -> steps.add("public closed"),
            () -> steps.add("admin closed"),
            worker,
            () -> steps.add("producer closed"),
            () -> steps.add("storage closed"));
    var closing = new Thread(shutdown);
    closing.start();
    try {
      for (int i = 0; i < 100 && !worker.isShutdown(); i++) Thread.sleep(10);
      assertFalse(steps.contains("producer closed"));
    } finally {
      release.countDown();
      closing.join(TimeUnit.SECONDS.toMillis(5));
    }
    assertFalse(closing.isAlive());
    assertEquals(
        List.of(
            "public closed",
            "admin closed",
            "batch committed",
            "producer closed",
            "storage closed"),
        steps);
    shutdown.run();
    assertEquals(5, steps.size());
  }
}
