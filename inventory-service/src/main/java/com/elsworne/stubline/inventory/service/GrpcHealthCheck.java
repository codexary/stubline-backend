package com.elsworne.stubline.inventory.service;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Container probe for the standard gRPC health service. */
public final class GrpcHealthCheck {
  private GrpcHealthCheck() {}

  public static void main(String[] args) {
    var builder =
        NettyChannelBuilder.forAddress(
            "localhost",
            Integer.parseInt(System.getenv().getOrDefault("STUBLINE_INVENTORY_PORT", "9090")));
    if (System.getenv("STUBLINE_GRPC_CA") == null) builder.usePlaintext();
    else {
      builder.overrideAuthority("inventory-service");
      try {
        builder.sslContext(
            GrpcSslContexts.forClient()
                .trustManager(Path.of(System.getenv("STUBLINE_GRPC_CA")).toFile())
                .keyManager(
                    Path.of(System.getenv("STUBLINE_GRPC_CLIENT_CERT")).toFile(),
                    Path.of(System.getenv("STUBLINE_GRPC_CLIENT_KEY")).toFile())
                .build());
      } catch (javax.net.ssl.SSLException e) {
        throw new IllegalStateException(e);
      }
    }
    var channel = builder.build();
    try {
      var status =
          HealthGrpc.newBlockingStub(channel)
              .withDeadlineAfter(2, TimeUnit.SECONDS)
              .check(
                  HealthCheckRequest.newBuilder()
                      .setService("stubline.inventory.v1.Inventory")
                      .build());
      if (status.getStatus() != HealthCheckResponse.ServingStatus.SERVING) System.exit(1);
    } finally {
      channel.shutdownNow();
    }
  }
}
