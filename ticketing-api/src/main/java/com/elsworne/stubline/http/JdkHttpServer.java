package com.elsworne.stubline.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serves the ticketing API with the JDK HTTP implementation. */
public final class JdkHttpServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public JdkHttpServer(int port, TicketingApi api) throws IOException {
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
      byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
      if (body.length > 1_048_576) {
        var problem =
            ProblemDetails.of(
                413,
                "Content Too Large",
                "request body exceeds 1048576 bytes",
                exchange.getRequestURI().getPath());
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
        exchange.sendResponseHeaders(413, problem.body().length);
        exchange.getResponseBody().write(problem.body());
        return;
      }
      HttpReply reply =
          api.handle(
              new HttpRequest(
                  exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      reply.headers().forEach(exchange.getResponseHeaders()::set);
      exchange.sendResponseHeaders(reply.status(), reply.body().length);
      exchange.getResponseBody().write(reply.body());
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
    server.stop(0);
    executor.shutdownNow();
  }
}
