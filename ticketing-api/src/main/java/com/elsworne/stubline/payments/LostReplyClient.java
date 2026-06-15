package com.elsworne.stubline.payments;

import com.elsworne.stubline.http.JdkHttpServer;
import com.elsworne.stubline.http.TicketingApi;
import com.elsworne.stubline.inventory.InMemoryInventory;
import com.elsworne.stubline.inventory.Seat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;

/** Demonstrates a client timeout followed by a payment retry. */
public final class LostReplyClient {
  private LostReplyClient() {}

  public static void main(String[] args) throws Exception {
    Clock clock = Clock.systemUTC();
    var provider = new PaymentProviderStub(350);
    String body = "{\"orderId\":\"order-1\",\"amountCents\":2500}";
    try (var client = HttpClient.newHttpClient()) {
      try (var server = server(clock, provider)) {
        server.start();
        URI uri = URI.create("http://localhost:" + server.port() + "/payments");
        // Warm the connection before applying the deliberately short payment timeout.
        client.send(
            HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding());
        try {
          client.send(
              request(uri, body, Duration.ofMillis(50)), HttpResponse.BodyHandlers.ofString());
          throw new IllegalStateException("Expected the first reply to time out");
        } catch (HttpTimeoutException e) {
          System.out.println("First reply timed out");
        }
        if (!provider.awaitStarted())
          throw new IllegalStateException("Provider never received request");
        // charges() waits for the synchronized charge to finish, without a timing guess.
        if (provider.charges() != 1) throw new IllegalStateException("Expected one initial charge");
      }
      System.out.println("Local idempotency store lost; restarting API with an empty store");
      try (var restarted = server(clock, provider)) {
        restarted.start();
        URI uri = URI.create("http://localhost:" + restarted.port() + "/payments");
        var retry =
            client.send(
                request(uri, body, Duration.ofSeconds(2)), HttpResponse.BodyHandlers.ofString());
        System.out.println("Retry status: " + retry.statusCode());
        System.out.println("Retry body: " + retry.body());
        System.out.println("Provider charges: " + provider.charges());
        if (retry.statusCode() != 201 || provider.charges() != 1) {
          throw new IllegalStateException("Retry did not preserve one charge");
        }
      }
    }
  }

  private static JdkHttpServer server(Clock clock, PaymentProviderStub provider) throws Exception {
    var payments = new PaymentService(new InMemoryIdempotencyStore(clock), provider, clock);
    var inventory = new InMemoryInventory();
    inventory.add(new Seat("A1", "concert-1", true));
    return new JdkHttpServer(0, new TicketingApi(inventory, payments));
  }

  private static HttpRequest request(URI uri, String body, Duration timeout) {
    return HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Idempotency-Key", "lost-reply-demo")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }
}
