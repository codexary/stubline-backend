package com.elsworne.stubline.inventory.service;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.concurrent.CountDownLatch;

/** Starts the inventory gRPC server with the standard health service. */
public final class InventoryMain {
  private InventoryMain() {}

  public static void main(String[] args) throws Exception {
    var inventory =
        new PostgresInventory(
            requireEnv("STUBLINE_JDBC_URL"),
            requireEnv("STUBLINE_DB_USER"),
            requireEnv("STUBLINE_DB_PASSWORD"),
            PostgresInventory.Strategy.valueOf(
                System.getenv().getOrDefault("STUBLINE_DB_STRATEGY", "ROW_LOCK")));
    seedDemoSeats(inventory);
    var health = new HealthStatusManager();
    Server server =
        NettyServerBuilder.forPort(
                Integer.parseInt(System.getenv().getOrDefault("STUBLINE_INVENTORY_PORT", "9090")))
            .addService(new InventoryGrpcService(inventory))
            .addService(health.getHealthService())
            .build()
            .start();
    var readiness = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    readiness.scheduleAtFixedRate(
        () ->
            health.setStatus(
                "stubline.inventory.v1.Inventory",
                inventory.ready()
                    ? HealthCheckResponse.ServingStatus.SERVING
                    : HealthCheckResponse.ServingStatus.NOT_SERVING),
        0,
        2,
        java.util.concurrent.TimeUnit.SECONDS);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  health.setStatus(
                      "stubline.inventory.v1.Inventory",
                      HealthCheckResponse.ServingStatus.NOT_SERVING);
                  readiness.shutdownNow();
                  server.shutdown();
                  try {
                    if (!server.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                      server.shutdownNow();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    server.shutdownNow();
                  }
                  inventory.close();
                }));
    new CountDownLatch(1).await();
  }

  static void seedDemoSeats(PostgresInventory inventory) throws java.sql.SQLException {
    inventory.add("concert-1", "A1");
    inventory.add("concert-1", "A2");
    inventory.add("concert-2", "B1");
    inventory.add("concert-2", "B2");
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }
}
