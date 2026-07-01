package com.elsworne.stubline.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.http.HttpRequest;
import com.elsworne.stubline.http.TicketingApi;
import com.elsworne.stubline.inventory.grpc.InventoryGrpc;
import com.elsworne.stubline.inventory.grpc.ListSeatsReply;
import com.elsworne.stubline.inventory.grpc.ListSeatsRequest;
import com.elsworne.stubline.inventory.grpc.ReserveReply;
import com.elsworne.stubline.inventory.grpc.ReserveRequest;
import com.elsworne.stubline.inventory.grpc.SeatState;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HTTP mapping exercised through a real generated stub and in-process gRPC transport. */
class GrpcInventoryClientTest {
  private static class Service extends InventoryGrpc.InventoryImplBase {
    @Override
    public void listSeats(ListSeatsRequest request, StreamObserver<ListSeatsReply> reply) {
      reply.onNext(
          ListSeatsReply.newBuilder()
              .addSeats(
                  SeatState.newBuilder()
                      .setId("A1")
                      .setEventId(request.getEventId())
                      .setAvailable(true))
              .build());
      reply.onCompleted();
    }

    @Override
    public void reserve(ReserveRequest request, StreamObserver<ReserveReply> reply) {
      reply.onNext(ReserveReply.getDefaultInstance());
      reply.onCompleted();
    }
  }

  private static int response(Service service, boolean reserve, Duration deadline)
      throws Exception {
    String name = InProcessServerBuilder.generateName();
    var server =
        InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
    try (var client =
        new GrpcInventoryClient(
            InProcessChannelBuilder.forName(name).directExecutor().build(), deadline)) {
      var api = new TicketingApi(client);
      return api.handle(
              reserve
                  ? new HttpRequest(
                      "POST",
                      "/orders",
                      Map.of(),
                      "{\"eventId\":\"concert\",\"seatId\":\"A1\"}".getBytes())
                  : new HttpRequest("GET", "/events/concert/seats", Map.of(), new byte[0]))
          .status();
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void found() throws Exception {
    assertEquals(200, response(new Service(), false, Duration.ofSeconds(1)));
  }

  @Test
  void notFound() throws Exception {
    assertEquals(
        404,
        response(
            new Service() {
              @Override
              public void listSeats(
                  ListSeatsRequest request, StreamObserver<ListSeatsReply> reply) {
                reply.onError(Status.NOT_FOUND.asRuntimeException());
              }
            },
            false,
            Duration.ofSeconds(1)));
  }

  @Test
  void takenSeat() throws Exception {
    assertEquals(
        409,
        response(
            new Service() {
              @Override
              public void reserve(ReserveRequest request, StreamObserver<ReserveReply> reply) {
                reply.onError(Status.FAILED_PRECONDITION.asRuntimeException());
              }
            },
            true,
            Duration.ofSeconds(1)));
  }

  @Test
  void deadlineExceeded() throws Exception {
    assertEquals(
        504,
        response(
            new Service() {
              @Override
              public void listSeats(
                  ListSeatsRequest request, StreamObserver<ListSeatsReply> reply) {
                /* intentionally never responds */
              }
            },
            false,
            Duration.ofMillis(10)));
  }

  @Test
  void serverUnavailable() throws Exception {
    var channel = InProcessChannelBuilder.forName("absent-server").build();
    try (var client = new GrpcInventoryClient(channel, Duration.ofMillis(100))) {
      assertEquals(
          503,
          new TicketingApi(client)
              .handle(new HttpRequest("GET", "/events/concert/seats", Map.of(), new byte[0]))
              .status());
    }
  }

  @Test
  void readinessReflectsGrpcHealthWithoutCascading() throws Exception {
    String name = InProcessServerBuilder.generateName();
    var health = new io.grpc.protobuf.services.HealthStatusManager();
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
      health.setStatus("", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
      health.setStatus(
          "stubline.inventory.v1.Inventory",
          io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
      assertTrue(client.ready());
      health.setStatus(
          "stubline.inventory.v1.Inventory",
          io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING);
      assertFalse(client.ready());
      assertEquals(
          503,
          new TicketingApi(client)
              .handle(new HttpRequest("GET", "/health/ready", Map.of(), new byte[0]))
              .status());
      server.shutdownNow();
      assertFalse(client.ready());
    } finally {
      server.shutdownNow();
    }
  }
}
