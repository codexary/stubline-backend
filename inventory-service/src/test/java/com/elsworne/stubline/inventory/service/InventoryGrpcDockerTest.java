package com.elsworne.stubline.inventory.service;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.inventory.grpc.InventoryGrpc;
import com.elsworne.stubline.inventory.grpc.ListSeatsRequest;
import com.elsworne.stubline.inventory.grpc.ReserveRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;

/** End-to-end generated client, network gRPC server, and PostgreSQL reservation. */
@Tag("docker")
@EnabledIfSystemProperty(named = "run.docker", matches = "true")
class InventoryGrpcDockerTest {
  @Test
  void reservePersistsAndSecondBuyerFails() throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:18.6-alpine3.24")) {
      postgres.start();
      try (var inventory =
          new PostgresInventory(
              postgres.getJdbcUrl(),
              postgres.getUsername(),
              postgres.getPassword(),
              PostgresInventory.Strategy.ROW_LOCK)) {
        inventory.add("concert", "A1");
        var server =
            NettyServerBuilder.forPort(0)
                .addService(new InventoryGrpcService(inventory))
                .build()
                .start();
        var channel =
            NettyChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
          var stub = InventoryGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
          var request = ReserveRequest.newBuilder().setEventId("concert").setSeatId("A1").build();
          stub.reserve(request);
          assertFalse(
              stub.listSeats(ListSeatsRequest.newBuilder().setEventId("concert").build())
                  .getSeats(0)
                  .getAvailable());
          var error = assertThrows(StatusRuntimeException.class, () -> stub.reserve(request));
          assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
        } finally {
          channel.shutdownNow();
          server.shutdownNow();
        }
      }
    }
  }
}
