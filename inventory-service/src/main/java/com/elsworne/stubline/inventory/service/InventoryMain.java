package com.elsworne.stubline.inventory.service;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.services.HealthStatusManager;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Starts the inventory gRPC server with the standard health service. */
public final class InventoryMain {
  private InventoryMain() {}

  public static void main(String[] args) throws Exception {
    var inventory =
        new PostgresInventory(
            System.getenv()
                .getOrDefault("STUBLINE_JDBC_URL", "jdbc:postgresql://localhost:5432/inventory"),
            System.getenv().getOrDefault("STUBLINE_DB_USER", "inventory"),
            required("STUBLINE_DB_PASSWORD"),
            PostgresInventory.Strategy.valueOf(
                System.getenv().getOrDefault("STUBLINE_DB_STRATEGY", "ROW_LOCK")));
    seedDemoSeats(inventory);
    var health = new HealthStatusManager();
    var builder =
        NettyServerBuilder.forPort(
            Integer.parseInt(System.getenv().getOrDefault("STUBLINE_INVENTORY_PORT", "9090")));
    if (System.getenv("STUBLINE_GRPC_CA") != null)
      builder.sslContext(
          GrpcSslContexts.forServer(
                  Path.of(System.getenv("STUBLINE_GRPC_CERT")).toFile(),
                  Path.of(System.getenv("STUBLINE_GRPC_KEY")).toFile())
              .trustManager(Path.of(System.getenv("STUBLINE_GRPC_CA")).toFile())
              .clientAuth(ClientAuth.REQUIRE)
              .build());
    Server server =
        builder
            .maxConnectionAge(15, TimeUnit.SECONDS)
            .maxConnectionAgeGrace(5, TimeUnit.SECONDS)
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

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing required setting: " + name);
    return value;
  }
}
