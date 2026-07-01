package com.elsworne.stubline.inventory.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("docker")
@EnabledIfSystemProperty(named = "run.docker", matches = "true")
class PostgresInventoryDockerTest {
  @Test
  void rowLockAdmitsOneBuyer() throws Exception {
    verifyOneWinner(PostgresInventory.Strategy.ROW_LOCK);
  }

  @Test
  void optimisticVersioningAdmitsOneBuyer() throws Exception {
    verifyOneWinner(PostgresInventory.Strategy.OPTIMISTIC);
  }

  private static void verifyOneWinner(PostgresInventory.Strategy strategy) throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:18.6-alpine3.24")) {
      postgres.start();
      try (var inventory =
          new PostgresInventory(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), strategy)) {
        inventory.add("concert", "A1");
        int winners = winners(() -> inventory.reserve("concert", "A1"));
        assertEquals(1, winners);
        assertTrue(inventory.retries() <= inventory.versionConflicts());
        assertTrue(inventory.versionConflicts() - inventory.retries() <= 1);
        System.out.printf(
            "%s winners=%d versionConflicts=%d retries=%d%n",
            strategy, winners, inventory.versionConflicts(), inventory.retries());
      }
    }
  }

  private interface Reservation {
    boolean reserve() throws Exception;
  }

  private static int winners(Reservation reservation) throws Exception {
    try (var workers = Executors.newFixedThreadPool(32)) {
      var start = new CountDownLatch(1);
      var ready = new CountDownLatch(32);
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 32; i++)
        futures.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return reservation.reserve();
                }));
      ready.await();
      start.countDown();
      int wins = 0;
      for (var future : futures) if (future.get()) wins++;
      return wins;
    }
  }
}
