package com.elsworne.stubline.http;

import com.elsworne.stubline.inventory.pg.PostgresInventory;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the raw or JDK ticketing HTTP server. */
public final class TicketingMain {
  private static final Logger LOG = LoggerFactory.getLogger(TicketingMain.class);

  private TicketingMain() {}

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "raw";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    long lookupDelayMillis = args.length > 4 ? Long.parseLong(args[4]) : 0;
    String jdbcUrl = System.getenv("STUBLINE_JDBC_URL");
    PostgresInventory postgres = null;
    TicketingApi api;
    if (jdbcUrl == null || jdbcUrl.isBlank()) api = TicketingApi.sample(lookupDelayMillis);
    else {
      postgres =
          new PostgresInventory(
              jdbcUrl,
              requireEnv("STUBLINE_DB_USER"),
              requireEnv("STUBLINE_DB_PASSWORD"),
              PostgresInventory.Strategy.valueOf(
                  System.getenv().getOrDefault("STUBLINE_DB_STRATEGY", "ROW_LOCK")));
      postgres.add("concert-1", "A1");
      postgres.add("concert-1", "A2");
      api = new TicketingApi(postgres, lookupDelayMillis);
    }
    ServerExecutor strategy =
        args.length > 2
            ? ServerExecutor.valueOf(args[2].toUpperCase(java.util.Locale.ROOT))
            : ServerExecutor.VIRTUAL;
    int poolSize = args.length > 3 ? Integer.parseInt(args[3]) : 16;
    int adminPort = Integer.parseInt(System.getenv().getOrDefault("STUBLINE_ADMIN_PORT", "8081"));
    try (var admin = new AdminHttpServer(adminPort, api, strategy, poolSize)) {
      admin.start();
      LOG.info("Admin HTTP server listening on loopback port {}", admin.port());
      if (mode.equals("raw")) {
        try (var server = new RawHttpServer(port, api, strategy, poolSize)) {
          server.start();
          LOG.info("Raw HTTP server listening on {}", server.port());
          new CountDownLatch(1).await();
        }
      } else if (mode.equals("jdk")) {
        try (var server = new JdkHttpServer(port, api, strategy, poolSize)) {
          server.start();
          LOG.info("JDK HTTP server listening on {} with {}", server.port(), strategy);
          new CountDownLatch(1).await();
        }
      } else throw new IllegalArgumentException("Use raw or jdk");
    } finally {
      if (postgres != null) postgres.close();
    }
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException(name + " is required when STUBLINE_JDBC_URL is set");
    return value;
  }
}
