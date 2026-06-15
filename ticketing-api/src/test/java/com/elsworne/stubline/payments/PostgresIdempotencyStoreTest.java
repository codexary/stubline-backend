package com.elsworne.stubline.payments;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("docker")
@EnabledIfSystemProperty(named = "run.docker", matches = "true")
class PostgresIdempotencyStoreTest extends IdempotencyStoreContract {
  private static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer("postgres:18.6-trixie");

  @BeforeAll
  static void startDatabase() {
    DATABASE.start();
  }

  @AfterAll
  static void stopDatabase() {
    DATABASE.close();
  }

  @Override
  IdempotencyStore createStore() {
    var result =
        new PostgresIdempotencyStore(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword(), clock);
    result.initialize();
    try (var connection =
            java.sql.DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("TRUNCATE payment_idempotency");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    return result;
  }

  @AfterEach
  void closePool() {
    ((PostgresIdempotencyStore) store).close();
  }

  @Test
  void persistsReplayAcrossStoreInstancesAndRejectsChangedBody() {
    var provider = new PaymentProviderStub(0);
    byte[] body = "{\"orderId\":\"o1\",\"amountCents\":500}".getBytes(StandardCharsets.UTF_8);
    var first = new PaymentService(store, provider, clock).pay("persisted", body);
    // Close the original pool as a real restart would; a new pool must read the saved reply.
    ((PostgresIdempotencyStore) store).close();
    try (var secondStore =
        new PostgresIdempotencyStore(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword(), clock)) {
      var service = new PaymentService(secondStore, provider, clock);
      var retry = service.pay("persisted", body);
      assertEquals(201, retry.status());
      assertArrayEquals(first.body(), retry.body());
      assertEquals(
          422,
          service
              .pay(
                  "persisted",
                  "{\"orderId\":\"o1\",\"amountCents\":600}".getBytes(StandardCharsets.UTF_8))
              .status());
      assertEquals(1, provider.charges());
    }
  }
}
