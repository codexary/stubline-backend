package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class RuntimeDiagnosticsTest {
  @Test
  void reportsJVMProcessorAndHeapLimits() throws Exception {
    try (var server = new AdminHttpServer(0, TicketingApi.sample(), ServerExecutor.VIRTUAL, 2);
        var client = HttpClient.newHttpClient()) {
      server.start();
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.port() + "/diagnostics/runtime"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var json = new ObjectMapper().readTree(response.body());
      assertEquals(200, response.statusCode());
      assertEquals(
          Runtime.getRuntime().availableProcessors(), json.path("availableProcessors").asInt());
      assertEquals(Runtime.getRuntime().maxMemory(), json.path("maxHeapBytes").asLong());
      assertTrue(json.has("containerMemoryLimitBytes"));
    }
  }
}
