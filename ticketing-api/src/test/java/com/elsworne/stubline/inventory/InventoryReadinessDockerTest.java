package com.elsworne.stubline.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.elsworne.stubline.http.HttpRequest;
import com.elsworne.stubline.http.TicketingApi;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;

/** Checks that a lost inventory database removes API readiness through named gRPC health. */
@Tag("docker")
@EnabledIfSystemProperty(named = "run.docker", matches = "true")
class InventoryReadinessDockerTest {
  private static final String SERVICE = "stubline.inventory.v1.Inventory";

  @Test
  void inventoryDatabaseLossMakesApiNotReady() throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:18.6-alpine3.24")) {
      postgres.start();
      String name = InProcessServerBuilder.generateName();
      var health = new HealthStatusManager();
      var server =
          InProcessServerBuilder.forName(name)
              .directExecutor()
              .addService(health.getHealthService())
              .build()
              .start();
      try (var client =
          new GrpcInventoryClient(
              InProcessChannelBuilder.forName(name).directExecutor().build(),
              Duration.ofSeconds(2))) {
        var api = new TicketingApi(client);
        updateHealth(health, postgres);
        assertEquals(200, readinessStatus(api));
        postgres.stop();
        updateHealth(health, postgres);
        assertEquals(503, readinessStatus(api));
      } finally {
        server.shutdownNow();
      }
    }
  }

  private static void updateHealth(HealthStatusManager health, PostgreSQLContainer<?> postgres) {
    try (var connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      health.setStatus(SERVICE, HealthCheckResponse.ServingStatus.SERVING);
    } catch (Exception e) {
      health.setStatus(SERVICE, HealthCheckResponse.ServingStatus.NOT_SERVING);
    }
  }

  private static int readinessStatus(TicketingApi api) {
    return api.handle(new HttpRequest("GET", "/health/ready", Map.of(), new byte[0])).status();
  }
}
