package com.elsworne.stubline.inventory;

import com.elsworne.stubline.inventory.grpc.InventoryGrpc;
import com.elsworne.stubline.inventory.grpc.ListSeatsRequest;
import com.elsworne.stubline.inventory.grpc.ReserveRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Generated-stub client; each invocation has a deadline. */
public final class GrpcInventoryClient implements SeatInventory, AutoCloseable {
  private static final String HEALTH_SERVICE = "stubline.inventory.v1.Inventory";
  private final ManagedChannel channel;
  private final Duration deadline;

  public GrpcInventoryClient(ManagedChannel channel, Duration deadline) {
    this.channel = channel;
    this.deadline = deadline;
  }

  @Override
  public List<Seat> seatsFor(String eventId) {
    var response =
        InventoryGrpc.newBlockingStub(channel)
            .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .listSeats(ListSeatsRequest.newBuilder().setEventId(eventId).build());
    return response.getSeatsList().stream()
        .map(s -> new Seat(s.getId(), s.getEventId(), s.getAvailable()))
        .toList();
  }

  @Override
  public boolean reserve(String eventId, String seatId) {
    try {
      InventoryGrpc.newBlockingStub(channel)
          .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
          .reserve(ReserveRequest.newBuilder().setEventId(eventId).setSeatId(seatId).build());
      return true;
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) return false;
      throw e;
    }
  }

  @Override
  public boolean ready() {
    try {
      return HealthGrpc.newBlockingStub(channel)
              .withDeadlineAfter(
                  Math.min(500, Math.max(1, deadline.toMillis())), TimeUnit.MILLISECONDS)
              .check(HealthCheckRequest.newBuilder().setService(HEALTH_SERVICE).build())
              .getStatus()
          == HealthCheckResponse.ServingStatus.SERVING;
    } catch (StatusRuntimeException e) {
      return false;
    }
  }

  @Override
  public void close() {
    channel.shutdownNow();
  }
}
