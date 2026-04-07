package com.elsworne.stubline.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

/** Loopback-only listener for diagnostic routes. */
public final class AdminHttpServer implements AutoCloseable {
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor;

  public AdminHttpServer(int port, TicketingApi api, ServerExecutor strategy, int poolSize)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    executor = strategy.create(poolSize);
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            String path = exchange.getRequestURI().getPath();
            HttpReply reply =
                path.startsWith("/diagnostics/")
                    ? api.handleAdmin(
                        new HttpRequest(exchange.getRequestMethod(), path, Map.of(), new byte[0]))
                    : ProblemDetails.of(404, "Not Found", "route not found", path);
            reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            exchange
                .getResponseHeaders()
                .putIfAbsent("Content-Type", java.util.List.of("application/json; charset=utf-8"));
            exchange.sendResponseHeaders(reply.status(), reply.body().length);
            exchange.getResponseBody().write(reply.body());
          }
        });
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public void start() {
    server.start();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
