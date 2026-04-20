package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Checks real raw and JDK HTTP responses against the public OpenAPI contract. */
final class OpenApiContractTest {
  @Test
  void rawServer() throws Exception {
    try (var server = new RawHttpServer(0, TicketingApi.sample())) {
      server.start();
      exercise(server.port(), false);
    }
  }

  @Test
  void jdkServer() throws Exception {
    try (var server = new JdkHttpServer(0, TicketingApi.sample())) {
      server.start();
      exercise(server.port(), true);
    }
  }

  private static void exercise(int port, boolean checkLarge) throws Exception {
    var validator =
        OpenApiInteractionValidator.createFor(Path.of("..", "openapi.yaml").toString()).build();
    try (var client = HttpClient.newHttpClient()) {
      var seats =
          client.send(
              HttpRequest.newBuilder(uri(port, "/events/concert-1/seats")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      check(validator, "/events/concert-1/seats", Request.Method.GET, seats);
      var firstPage =
          client.send(
              HttpRequest.newBuilder(uri(port, "/events/concert-1/seats?limit=1")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      check(validator, "/events/concert-1/seats", Request.Method.GET, firstPage);
      var pageJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(firstPage.body());
      assertEquals(1, pageJson.path("seats").size());
      var nextPage =
          client.send(
              HttpRequest.newBuilder(
                      uri(
                          port,
                          "/events/concert-1/seats?limit=1&cursor="
                              + pageJson.path("next").asText()))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      check(validator, "/events/concert-1/seats", Request.Method.GET, nextPage);
      assertEquals(
          1,
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(nextPage.body())
              .path("seats")
              .size());
      var badLimit =
          client.send(
              HttpRequest.newBuilder(uri(port, "/events/concert-1/seats?limit=101")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, badLimit.statusCode());
      check(validator, "/events/concert-1/seats", Request.Method.GET, badLimit);
      var order =
          HttpRequest.newBuilder(uri(port, "/orders"))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"eventId\":\"concert-1\",\"seatId\":\"A1\"}"))
              .build();
      check(
          validator,
          "/orders",
          Request.Method.POST,
          client.send(order, HttpResponse.BodyHandlers.ofString()));
      check(
          validator,
          "/orders",
          Request.Method.POST,
          client.send(order, HttpResponse.BodyHandlers.ofString()));
      if (checkLarge) {
        var large =
            client.send(
                HttpRequest.newBuilder(uri(port, "/orders"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1_048_577]))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, large.statusCode());
        check(validator, "/orders", Request.Method.POST, large);
      }
      for (String body : new String[] {"{\"eventId\":42}", "{\"seatId\":\"A2\"}", "{"}) {
        var invalid =
            HttpRequest.newBuilder(uri(port, "/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var bad = client.send(invalid, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode());
        check(validator, "/orders", Request.Method.POST, bad);
      }
    }
  }

  private static URI uri(int port, String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static void check(
      OpenApiInteractionValidator validator,
      String path,
      Request.Method method,
      HttpResponse<String> actual) {
    var response =
        SimpleResponse.Builder.status(actual.statusCode())
            .withContentType(actual.headers().firstValue("Content-Type").orElse(""))
            .withBody(actual.body())
            .build();
    var report = validator.validateResponse(path, method, response);
    assertFalse(report.hasErrors(), report.getMessages().toString());
  }
}
