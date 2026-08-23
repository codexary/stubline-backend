package com.elsworne.stubline.inventory.service;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.util.concurrent.TimeUnit;

/** Container probe for the standard gRPC health service. */
public final class GrpcHealthCheck {
  private GrpcHealthCheck() {}

  public static void main(String[] args) {
    var channel =
        NettyChannelBuilder.forAddress(
                "localhost",
                Integer.parseInt(System.getenv().getOrDefault("STUBLINE_INVENTORY_PORT", "9090")))
            .usePlaintext()
            .build();
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
