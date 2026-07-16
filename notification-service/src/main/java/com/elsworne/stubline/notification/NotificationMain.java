package com.elsworne.stubline.notification;

import java.time.Duration;

/** Runs the notification consumer group. */
public final class NotificationMain {
  private NotificationMain() {}

  public static void main(String[] args) throws Exception {
    try (var store =
            new NotificationStore(
                requireEnv("STUBLINE_JDBC_URL"),
                requireEnv("STUBLINE_DB_USER"),
                requireEnv("STUBLINE_DB_PASSWORD"));
        var consumer =
            new NotificationConsumer(
                System.getenv().getOrDefault("STUBLINE_KAFKA_BOOTSTRAP", "localhost:9092"),
                "stubline-notifications",
                store)) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      consumer.close();
                      store.close();
                    } catch (Exception ignored) {
                    }
                  }));
      while (true) consumer.pollOnce(Duration.ofSeconds(1), () -> {});
    }
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }
}
