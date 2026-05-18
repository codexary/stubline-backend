package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GracefulShutdownTest {
  @Test
  void inFlightRequestCompletesOnClose() throws Exception {
    var api = TicketingApi.sample(300);
    try (var server = new JdkHttpServer(0, api);
        var client = HttpClient.newHttpClient()) {
      server.start();
      CompletableFuture<HttpResponse<String>> future =
          client.sendAsync(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.port() + "/events/concert-1/seats"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (api.peakInFlight() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
      server.close();
      assertEquals(200, future.get().statusCode());
    }
  }
}
