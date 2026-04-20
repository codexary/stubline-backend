package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpServersTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void rawServerServesSeatsAndOrders() throws Exception {
    try (var server = new RawHttpServer(0, TicketingApi.sample())) {
      server.start();
      exercise(server.port());
    }
  }

  @Test
  void jdkServerServesSeatsAndOrders() throws Exception {
    try (var server = new JdkHttpServer(0, TicketingApi.sample())) {
      server.start();
      exercise(server.port());
    }
  }

  private void exercise(int port) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      URI seats = URI.create("http://localhost:" + port + "/events/concert-1/seats");
      var get = HttpRequest.newBuilder(seats).timeout(Duration.ofSeconds(3)).GET().build();
      var first = client.send(get, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, first.statusCode());
      assertEquals(2, json.readTree(first.body()).path("seats").size());
      URI orders = URI.create("http://localhost:" + port + "/orders");
      var post =
          HttpRequest.newBuilder(orders)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"eventId\":\"concert-1\",\"seatId\":\"A1\"}"))
              .build();
      var created = client.send(post, HttpResponse.BodyHandlers.ofString());
      assertEquals(201, created.statusCode());
      assertEquals("A1", json.readTree(created.body()).path("seatId").asText());
      assertEquals(409, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertFalse(
          json.readTree(client.send(get, HttpResponse.BodyHandlers.ofString()).body())
              .path("seats")
              .get(0)
              .path("available")
              .asBoolean());
    }
  }
}
