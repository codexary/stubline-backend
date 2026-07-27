package com.elsworne.stubline.tickets;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.orders.Order;
import com.elsworne.stubline.orders.outbox.OrderStore;
import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL row locking keeps ticket issuance idempotent across callers. */
@Tag("docker")
@EnabledIfSystemProperty(named = "run.docker", matches = "true")
class TicketIssuanceDockerTest {
  @Test
  void concurrentIssuersUploadOnce() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:18.6-trixie")) {
      database.start();
      try (var orders =
              new OrderStore(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        orders.save(new Order("order-1", "concert", "A1", Instant.EPOCH), "buyer", 2500, "AUD");
        var uploading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var uploads = new AtomicInteger();
        var storage =
            new TicketStorage() {
              @Override
              public void put(String key, byte[] pdf) {
                assertEquals("tickets/order-1.pdf", key);
                assertEquals((byte) '%', pdf[0]);
                uploads.incrementAndGet();
                uploading.countDown();
                try {
                  if (!release.await(30, TimeUnit.SECONDS))
                    throw new IllegalStateException("Upload blocked");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
              }

              @Override
              public URI presignGet(String key, Duration lifetime) {
                throw new UnsupportedOperationException();
              }
            };
        var tickets = new TicketService(orders, storage);
        var first =
            workers.submit(
                () -> {
                  tickets.issue("order-1");
                  return null;
                });
        Future<?> second;
        try {
          assertTrue(uploading.await(10, TimeUnit.SECONDS), "first upload did not start");
          second =
              workers.submit(
                  () -> {
                    secondStarted.countDown();
                    tickets.issue("order-1");
                    return null;
                  });
          assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
          assertTrue(
              awaitTicketRowLock(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword()),
              "second issuer never waited on the order row lock");
        } finally {
          release.countDown();
        }
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertEquals(1, uploads.get());
        assertEquals("tickets/order-1.pdf", orders.ticketKey("order-1").orElseThrow());
      }
    }
  }

  private static boolean awaitTicketRowLock(String url, String user, String password)
      throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement =
            connection.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query LIKE 'SELECT ticket_object_key FROM orders WHERE id = % FOR UPDATE%'")) {
      do {
        try (var rows = statement.executeQuery()) {
          rows.next();
          if (rows.getLong(1) > 0) return true;
        }
        Thread.sleep(20);
      } while (System.nanoTime() < deadline);
      return false;
    }
  }

  @Test
  void failedUploadLeavesKeyUnsetForRetry() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:18.6-trixie")) {
      database.start();
      try (var orders =
          new OrderStore(database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
        orders.save(new Order("order-1", "concert", "A1", Instant.EPOCH), "buyer", 2500, "AUD");
        assertThrows(
            IllegalStateException.class,
            () ->
                orders.issueIfAbsent(
                    "order-1",
                    "tickets/order-1.pdf",
                    () -> {
                      throw new IllegalStateException("simulated upload failure");
                    }));
        assertTrue(orders.ticketKey("order-1").isEmpty());
        orders.issueIfAbsent("order-1", "tickets/order-1.pdf", () -> {});
        assertEquals("tickets/order-1.pdf", orders.ticketKey("order-1").orElseThrow());
      }
    }
  }
}
