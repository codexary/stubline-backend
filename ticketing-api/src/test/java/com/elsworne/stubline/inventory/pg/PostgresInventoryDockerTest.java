package com.elsworne.stubline.inventory.pg;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.inventory.ReservationRaceTest;
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
        int winners = ReservationRaceTest.winners(() -> inventory.reserve("concert", "A1"));
        assertEquals(1, winners);
        assertTrue(inventory.retries() <= inventory.versionConflicts());
        assertTrue(inventory.versionConflicts() - inventory.retries() <= 1);
        System.out.printf(
            "%s winners=%d versionConflicts=%d retries=%d%n",
            strategy, winners, inventory.versionConflicts(), inventory.retries());
      }
    }
  }
}
