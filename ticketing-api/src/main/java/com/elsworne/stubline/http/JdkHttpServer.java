package com.elsworne.stubline.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** Serves the ticketing API with the JDK HTTP implementation. */
public final class JdkHttpServer implements AutoCloseable {
  static {
    System.setProperty("sun.net.httpserver.maxReqHeaderSize", "65536");
    System.setProperty("sun.net.httpserver.maxReqTime", "5");
  }

  private final HttpServer server;
  private final ExecutorService executor;

  public JdkHttpServer(int port, TicketingApi api) throws IOException {
    this(port, api, ServerExecutor.VIRTUAL, 16);
  }

  public JdkHttpServer(int port, TicketingApi api, ServerExecutor strategy, int poolSize)
      throws IOException {
    executor = strategy.create(poolSize);
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(executor);
    server.createContext("/", exchange -> handle(exchange, api));
  }

  private static void handle(HttpExchange exchange, TicketingApi api) throws IOException {
    try (exchange) {
      var headers = new HashMap<String, String>();
      exchange
          .getRequestHeaders()
          .forEach((key, values) -> headers.put(key.toLowerCase(Locale.ROOT), values.getFirst()));
      int headerBytes = exchange.getRequestURI().toString().length();
      for (var entry : exchange.getRequestHeaders().entrySet())
        for (String value : entry.getValue())
          headerBytes += entry.getKey().length() + value.length() + 4;
      HttpReply reply;
      if (headerBytes > 8192) {
        reply =
            ProblemDetails.of(
                431,
                "Request Header Fields Too Large",
                "Request line or headers exceed limit",
                exchange.getRequestURI().getPath());
      } else {
        byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
        if (body.length > 1_048_576)
          reply =
              ProblemDetails.of(
                  413,
                  "Content Too Large",
                  "Request body exceeds limit",
                  exchange.getRequestURI().getPath());
        else if (exchange.getRequestURI().getPath().startsWith("/diagnostics/"))
          reply =
              ProblemDetails.of(
                  404, "Not Found", "route not found", exchange.getRequestURI().getPath());
        else
          reply =
              api.handle(
                  new HttpRequest(
                      exchange.getRequestMethod(),
                      exchange.getRequestURI().getPath(),
                      headers,
                      body,
                      exchange.getRequestURI().getRawQuery()));
      }
      reply = ProblemDetails.normalize(reply, exchange.getRequestURI().getPath());
      reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
      exchange
          .getResponseHeaders()
          .putIfAbsent("Content-Type", java.util.List.of("application/json; charset=utf-8"));
      if (exchange.getRequestMethod().equals("HEAD")) {
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(reply.body().length));
        exchange.sendResponseHeaders(reply.status(), -1);
      } else {
        exchange.sendResponseHeaders(reply.status(), reply.body().length);
        exchange.getResponseBody().write(reply.body());
      }
    }
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public void start() {
    server.start();
  }

  @Override
  public void close() {
    server.stop(5);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }
}
