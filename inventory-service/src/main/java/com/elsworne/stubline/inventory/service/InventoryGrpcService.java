package com.elsworne.stubline.inventory.service;

import com.elsworne.stubline.inventory.grpc.InventoryGrpc;
import com.elsworne.stubline.inventory.grpc.ListSeatsReply;
import com.elsworne.stubline.inventory.grpc.ListSeatsRequest;
import com.elsworne.stubline.inventory.grpc.ReserveReply;
import com.elsworne.stubline.inventory.grpc.ReserveRequest;
import com.elsworne.stubline.inventory.grpc.SeatState;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;

/** Exposes PostgreSQL seat inventory over the shared gRPC contract. */
public final class InventoryGrpcService extends InventoryGrpc.InventoryImplBase {
  private final PostgresInventory inventory;

  public InventoryGrpcService(PostgresInventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public void listSeats(ListSeatsRequest request, StreamObserver<ListSeatsReply> observer) {
    try {
      var seats = inventory.seatsFor(request.getEventId());
      if (seats.isEmpty()) {
        observer.onError(
            Status.NOT_FOUND.withDescription("event has no seats").asRuntimeException());
        return;
      }
      var reply = ListSeatsReply.newBuilder();
      for (var seat : seats) {
        reply.addSeats(
            SeatState.newBuilder()
                .setId(seat.id())
                .setEventId(seat.eventId())
                .setAvailable(seat.available()));
      }
      observer.onNext(reply.build());
      observer.onCompleted();
    } catch (SQLException e) {
      observer.onError(Status.UNAVAILABLE.withCause(e).asRuntimeException());
    }
  }

  @Override
  public void reserve(ReserveRequest request, StreamObserver<ReserveReply> observer) {
    try {
      if (!inventory.seatsFor(request.getEventId()).stream()
          .anyMatch(s -> s.id().equals(request.getSeatId()))) {
        observer.onError(Status.NOT_FOUND.withDescription("seat not found").asRuntimeException());
      } else if (!inventory.reserve(request.getEventId(), request.getSeatId())) {
        observer.onError(
            Status.FAILED_PRECONDITION.withDescription("seat taken").asRuntimeException());
      } else {
        observer.onNext(
            ReserveReply.newBuilder()
                .setEventId(request.getEventId())
                .setSeatId(request.getSeatId())
                .build());
        observer.onCompleted();
      }
    } catch (SQLException e) {
      observer.onError(Status.UNAVAILABLE.withCause(e).asRuntimeException());
    }
  }
}
