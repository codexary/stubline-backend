package com.elsworne.stubline.notification;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

/** Runs the notification consumer group. */
public final class NotificationMain {
  private NotificationMain() {}

  public static void main(String[] args) throws Exception {
    try (var store =
            new NotificationStore(
                required("STUBLINE_JDBC_URL"),
                required("STUBLINE_DB_USER"),
                required("STUBLINE_DB_PASSWORD"));
        var consumer =
            new NotificationConsumer(
                System.getenv().getOrDefault("STUBLINE_KAFKA_BOOTSTRAP", "localhost:9092"),
                "stubline-notifications",
                store)) {
      var health = HttpServer.create(new InetSocketAddress(8084), 0);
      health.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      for (String path : new String[] {"/health", "/health/live"}) {
        health.createContext(
            path,
            exchange -> {
              exchange.sendResponseHeaders(200, -1);
              exchange.close();
            });
      }
      health.createContext(
          "/health/ready",
          exchange -> {
            exchange.sendResponseHeaders(store.ready() ? 200 : 503, -1);
            exchange.close();
          });
      health.start();
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    health.stop(5);
                    try {
                      consumer.close();
                      store.close();
                    } catch (Exception ignored) {
                    }
                  }));
      long pause =
          Long.parseLong(System.getenv().getOrDefault("STUBLINE_PAUSE_BEFORE_COMMIT_MS", "0"));
      while (true)
        consumer.pollOnce(
            Duration.ofSeconds(1),
            () -> {
              if (pause > 0) {
                org.slf4j.LoggerFactory.getLogger(NotificationMain.class)
                    .info("Pausing before offset commit");
                try {
                  Thread.sleep(pause);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing required setting: " + name);
    return value;
  }
}
