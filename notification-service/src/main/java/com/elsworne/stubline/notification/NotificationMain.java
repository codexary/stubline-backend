package com.elsworne.stubline.notification;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

/** Runs the notification consumer group. */
public final class NotificationMain {
  private NotificationMain() {}

  public static void main(String[] args) throws Exception {
    var health = HttpServer.create(new InetSocketAddress(8084), 0);
    health.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    health.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    health.start();
    try (var store =
            new NotificationStore(
                System.getenv()
                    .getOrDefault(
                        "STUBLINE_JDBC_URL", "jdbc:postgresql://localhost:5432/notifications"),
                System.getenv().getOrDefault("STUBLINE_DB_USER", "notifications"),
                required("STUBLINE_DB_PASSWORD"));
        var consumer =
            new NotificationConsumer(
                System.getenv().getOrDefault("STUBLINE_KAFKA_BOOTSTRAP", "localhost:9092"),
                "stubline-notifications",
                store)) {
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
