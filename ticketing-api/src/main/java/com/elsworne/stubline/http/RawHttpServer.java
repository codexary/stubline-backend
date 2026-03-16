package com.elsworne.stubline.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deliberately small HTTP/1.1 parser for the raw HTTP server framing experiment. */
public final class RawHttpServer implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(RawHttpServer.class);
  private static final int MAX_LINE = 8192;
  private static final int MAX_BODY = 1_048_576;
  private final TicketingApi api;
  private final ServerSocket server;
  private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
  private final ExecutorService acceptor = Executors.newSingleThreadExecutor();

  public RawHttpServer(int port, TicketingApi api) throws IOException {
    this.api = api;
    this.server = new ServerSocket(port);
  }

  public int port() {
    return server.getLocalPort();
  }

  public void start() {
    acceptor.submit(
        () -> {
          while (!server.isClosed()) {
            try {
              Socket socket = server.accept();
              clients.submit(() -> serve(socket));
            } catch (SocketException e) {
              if (!server.isClosed()) LOG.warn("Accept failed", e);
            } catch (IOException e) {
              LOG.warn("Accept failed", e);
            }
          }
        });
  }

  private void serve(Socket socket) {
    try (socket;
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream()) {
      socket.setSoTimeout(30_000);
      try {
        while (true) {
          String line = readLine(in);
          if (line == null) return;
          if (line.isEmpty()) continue;
          String[] requestLine = line.split(" ", -1);
          if (requestLine.length != 3
              || !requestLine[2].equals("HTTP/1.1")
              || !requestLine[1].startsWith("/")) {
            write(out, ProblemDetails.of(400, "Bad Request", "invalid request line", "/"), true);
            return;
          }
          Map<String, String> headers = new HashMap<>();
          while (true) {
            line = readLine(in);
            if (line == null) throw new BadRequestException();
            if (line.isEmpty()) break;
            if (headers.size() >= 100) throw new BadRequestException();
            int colon = line.indexOf(':');
            if (colon <= 0) throw new BadRequestException();
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.putIfAbsent(key, line.substring(colon + 1).trim()) != null
                && key.equals("content-length")) {
              throw new BadRequestException();
            }
          }
          if (!headers.containsKey("host") || headers.containsKey("transfer-encoding"))
            throw new BadRequestException();
          if (requestLine[0].equals("POST") && !headers.containsKey("content-length"))
            throw new BadRequestException();
          int length;
          try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
          } catch (NumberFormatException e) {
            throw new BadRequestException();
          }
          if (length < 0) throw new BadRequestException();
          if (length > MAX_BODY) throw new TooLargeBodyException();
          byte[] body = in.readNBytes(length);
          if (body.length != length) return;
          boolean close = "close".equalsIgnoreCase(headers.getOrDefault("connection", ""));
          write(
              out,
              api.handle(new HttpRequest(requestLine[0], requestLine[1], headers, body)),
              close);
          if (close) return;
        }
      } catch (TooLargeBodyException e) {
        write(
            out,
            ProblemDetails.of(413, "Content Too Large", "Request body exceeds limit", "/"),
            true);
      } catch (BadRequestException e) {
        write(out, ProblemDetails.of(400, "Bad Request", "invalid framing", "/"), true);
      }
    } catch (IOException e) {
      if (!server.isClosed()) LOG.debug("Client disconnected", e);
    }
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int previous = -1;
    while (true) {
      int value = in.read();
      if (value < 0) {
        if (bytes.size() == 0) return null;
        throw new BadRequestException();
      }
      if (previous == '\r' && value == '\n') {
        byte[] raw = bytes.toByteArray();
        return new String(raw, 0, raw.length - 1, StandardCharsets.ISO_8859_1);
      }
      bytes.write(value);
      if (bytes.size() > MAX_LINE) throw new BadRequestException();
      previous = value;
    }
  }

  private static void write(OutputStream out, HttpReply reply, boolean close) throws IOException {
    String head =
        "HTTP/1.1 "
            + reply.status()
            + " "
            + reply.reason()
            + "\r\n"
            + "Content-Type: "
            + reply.headers().getOrDefault("Content-Type", "application/json; charset=utf-8")
            + "\r\n"
            + "Content-Length: "
            + reply.body().length
            + "\r\n"
            + "Connection: "
            + (close ? "close" : "keep-alive")
            + "\r\n"
            + reply.headers().entrySet().stream()
                .filter(entry -> !entry.getKey().equalsIgnoreCase("Content-Type"))
                .map(entry -> entry.getKey() + ": " + entry.getValue() + "\r\n")
                .collect(java.util.stream.Collectors.joining())
            + "\r\n";
    out.write(head.getBytes(StandardCharsets.ISO_8859_1));
    out.write(reply.body());
    out.flush();
  }

  @Override
  public void close() throws IOException {
    server.close();
    acceptor.shutdownNow();
    clients.shutdownNow();
  }

  private static final class TooLargeBodyException extends IOException {}

  private static final class BadRequestException extends IOException {}
}
