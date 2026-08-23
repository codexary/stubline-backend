package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ExecutorStrategiesTest {
  @Test
  void virtualWorkersReportIsVirtual() throws Exception {
    assertEquals("{\"virtual\":true}", threadDiagnostic(ServerExecutor.VIRTUAL));
  }

  @Test
  void fixedPoolWorkersReportPlatformThreads() throws Exception {
    assertEquals("{\"virtual\":false}", threadDiagnostic(ServerExecutor.FIXED_POOL));
  }

  @Test
  void platformWorkersReportPlatformThreads() throws Exception {
    assertEquals("{\"virtual\":false}", threadDiagnostic(ServerExecutor.THREAD_PER_REQUEST));
  }

  private static String threadDiagnostic(ServerExecutor strategy) throws Exception {
    try (var server = new AdminHttpServer(0, TicketingApi.sample(), strategy, 2);
        var client = HttpClient.newHttpClient()) {
      server.start();
      return client
          .send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + server.port() + "/diagnostics/thread"))
                  .build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    }
  }

  @Test
  void fixedPoolNeverRunsMoreThanTwoRequestsConcurrently() throws Exception {
    var api = TicketingApi.sample(100);
    try (var server = new JdkHttpServer(0, api, ServerExecutor.FIXED_POOL, 2);
        var client = HttpClient.newHttpClient();
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            callers.submit(
                () ->
                    client
                        .send(
                            HttpRequest.newBuilder(
                                    URI.create(
                                        "http://localhost:"
                                            + server.port()
                                            + "/events/concert-1/seats"))
                                .build(),
                            HttpResponse.BodyHandlers.discarding())
                        .statusCode()));
      for (var future : futures) assertEquals(200, future.get());
      assertTrue(api.peakInFlight() <= 2);
      assertTrue(api.peakInFlight() >= 2);
    }
  }
}
