package com.elsworne.stubline.http;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Closes the ticketing listeners, relay, and dependencies in shutdown order. */
final class TicketingShutdown implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(TicketingShutdown.class);
  private final AtomicBoolean started = new AtomicBoolean();
  private final AutoCloseable publicListener;
  private final AutoCloseable adminListener;
  private final ExecutorService relayWorker;
  private final AutoCloseable[] dependencies;

  TicketingShutdown(
      AutoCloseable publicListener,
      AutoCloseable adminListener,
      ExecutorService relayWorker,
      AutoCloseable... dependencies) {
    this.publicListener = publicListener;
    this.adminListener = adminListener;
    this.relayWorker = relayWorker;
    this.dependencies = dependencies;
  }

  @Override
  public void run() {
    if (!started.compareAndSet(false, true)) return;
    close("public listener", publicListener);
    close("admin listener", adminListener);
    if (relayWorker != null) {
      LOG.info("Stopping outbox relay worker");
      relayWorker.shutdown();
      try {
        if (!relayWorker.awaitTermination(15, TimeUnit.SECONDS)) {
          LOG.warn("Outbox relay did not finish within 15s; interrupting current batch");
          relayWorker.shutdownNow();
          relayWorker.awaitTermination(2, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        relayWorker.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    String[] names = {
      "outbox producer",
      "ticket storage",
      "order pool",
      "inventory channel",
      "payment pool",
      "payment client"
    };
    for (int i = 0; i < dependencies.length; i++) close(names[i], dependencies[i]);
    LOG.info("Ticketing shutdown complete");
  }

  private static void close(String name, AutoCloseable resource) {
    if (resource == null) return;
    LOG.info("Closing {}", name);
    try {
      resource.close();
    } catch (Exception e) {
      LOG.warn("Could not close {}", name, e);
    }
  }
}
