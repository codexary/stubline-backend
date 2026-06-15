package com.elsworne.stubline.provider;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Configurable local stand-in for an external payment provider. */
public final class PaymentProviderMain {
  private PaymentProviderMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("PROVIDER_PORT", "8081"));
    long latency = Long.parseLong(System.getenv().getOrDefault("PROVIDER_LATENCY_MS", "0"));
    double errorRate = Double.parseDouble(System.getenv().getOrDefault("PROVIDER_ERROR_RATE", "0"));
    var outage =
        new java.util.concurrent.atomic.AtomicBoolean(
            Boolean.parseBoolean(System.getenv().getOrDefault("PROVIDER_OUTAGE", "false")));
    if (latency < 0 || errorRate < 0 || errorRate > 1)
      throw new IllegalArgumentException("Invalid provider settings");
    var server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/health",
        exchange -> {
          try (exchange) {
            if (exchange.getRequestMethod().equals("GET")
                || exchange.getRequestMethod().equals("HEAD")) {
              exchange.sendResponseHeaders(200, -1);
            } else {
              exchange.sendResponseHeaders(405, -1);
            }
          }
        });
    for (String path : new String[] {"/health/live", "/health/ready"}) {
      server.createContext(
          path,
          exchange -> {
            try (exchange) {
              exchange.sendResponseHeaders(200, -1);
            }
          });
    }
    server.createContext(
        "/admin/outage",
        exchange -> {
          try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
              exchange.sendResponseHeaders(405, -1);
              return;
            }
            String value =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!value.equals("true") && !value.equals("false")) {
              exchange.sendResponseHeaders(400, -1);
              return;
            }
            outage.set(Boolean.parseBoolean(value));
            exchange.sendResponseHeaders(204, -1);
          }
        });
    var charges =
        new ProviderCharges(
            Long.parseLong(System.getenv().getOrDefault("PROVIDER_PAYMENT_REPLY_DELAY_MS", "0")));
    server.createContext(
        "/admin/charges",
        exchange -> {
          try (exchange) {
            byte[] body =
                ("{\"charges\":" + charges.count() + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.createContext("/payments", charges::handle);
    for (String path : new String[] {"/charge", "/charge-status"}) {
      server.createContext(
          path,
          exchange -> {
            try (exchange) {
              try {
                Thread.sleep(latency);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              boolean fail = outage.get() || ThreadLocalRandom.current().nextDouble() < errorRate;
              int status = fail ? 503 : 200;
              byte[] body =
                  (fail ? "{\"status\":\"unavailable\"}" : "{\"status\":\"approved\"}")
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, body.length);
              exchange.getResponseBody().write(body);
            }
          });
    }
    server.start();
    System.out.printf(
        "payment-provider-stub listening=%d latencyMs=%d errorRate=%.2f outage=%b%n",
        port, latency, errorRate, outage.get());
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(10)));
    new CountDownLatch(1).await();
  }
}
