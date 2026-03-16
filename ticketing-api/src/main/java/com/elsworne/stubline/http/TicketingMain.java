package com.elsworne.stubline.http;

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
    var api = TicketingApi.sample();
    if (mode.equals("raw")) {
      try (var server = new RawHttpServer(port, api)) {
        server.start();
        LOG.info("Raw HTTP server listening on {}", server.port());
        new CountDownLatch(1).await();
      }
    } else if (mode.equals("jdk")) {
      try (var server = new JdkHttpServer(port, api)) {
        server.start();
        LOG.info("JDK HTTP server listening on {}", server.port());
        new CountDownLatch(1).await();
      }
    } else throw new IllegalArgumentException("Use raw or jdk");
  }
}
