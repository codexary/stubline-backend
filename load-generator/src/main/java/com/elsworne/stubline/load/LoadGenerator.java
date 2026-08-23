package com.elsworne.stubline.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Sends a fixed number of concurrent requests for a measured duration. */
public final class LoadGenerator {
  private LoadGenerator() {}

  public static void main(String[] args) throws Exception {
    URI uri =
        URI.create(args.length > 0 ? args[0] : "http://localhost:8080/events/concert-1/seats");
    int clients = args.length > 1 ? Integer.parseInt(args[1]) : 32;
    int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;
    if (clients < 1 || seconds < 1)
      throw new IllegalArgumentException("Positive clients and seconds required");
    long start = System.nanoTime();
    long deadline = start + TimeUnit.SECONDS.toNanos(seconds);
    List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    int[] completed = new int[clients];
    int[] timeouts = new int[clients];
    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var workers = Executors.newFixedThreadPool(clients)) {
      for (int i = 0; i < clients; i++) {
        final int clientId = i;
        workers.submit(
            () -> {
              var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build();
              while (System.nanoTime() < deadline) {
                long sent = System.nanoTime();
                try {
                  var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                  if (response.statusCode() == 200) {
                    latencies.add(System.nanoTime() - sent);
                    completed[clientId]++;
                  }
                } catch (java.net.http.HttpTimeoutException e) {
                  timeouts[clientId]++;
                } catch (Exception e) {
                  if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                  break;
                }
              }
            });
      }
      workers.shutdown();
      if (!workers.awaitTermination(seconds + 15L, TimeUnit.SECONDS)) workers.shutdownNow();
    }
    int empty = 0;
    for (int i = 0; i < clients; i++) {
      System.out.printf("client=%d completed=%d timeouts=%d%n", i, completed[i], timeouts[i]);
      if (completed[i] == 0) empty++;
    }
    System.out.printf("clientsWithNoCompletions=%d%n", empty);
    Collections.sort(latencies);
    System.out.printf(
        "requests=%d rps=%.1f p50=%.1fms p95=%.1fms p99=%.1fms%n",
        latencies.size(),
        latencies.size() / (double) seconds,
        percentile(latencies, 0.50),
        percentile(latencies, 0.95),
        percentile(latencies, 0.99));
  }

  private static double percentile(List<Long> sorted, double fraction) {
    return sorted.isEmpty()
        ? Double.NaN
        : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * fraction) - 1))
            / 1_000_000.0;
  }
}
