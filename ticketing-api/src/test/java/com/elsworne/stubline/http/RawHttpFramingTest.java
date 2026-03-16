package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RawHttpFramingTest {
  @Test
  void rejectsMalformedRequestLine() throws Exception {
    withServer(
        (socket) -> {
          socket
              .getOutputStream()
              .write(ascii("GET /events/concert-1/seats WRONG\r\nHost: localhost\r\n\r\n"));
          assertEquals(400, readReply(socket.getInputStream()).status());
        });
  }

  @Test
  void rejectsPostWithoutContentLength() throws Exception {
    withServer(
        (socket) -> {
          socket.getOutputStream().write(ascii("POST /orders HTTP/1.1\r\nHost: localhost\r\n\r\n"));
          assertEquals(400, readReply(socket.getInputStream()).status());
        });
  }

  @Test
  void waitsForBodySplitAcrossTcpWrites() throws Exception {
    withServer(
        (socket) -> {
          byte[] body = ascii("{\"eventId\":\"concert-1\",\"seatId\":\"A1\"}");
          socket
              .getOutputStream()
              .write(
                  ascii(
                      "POST /orders HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                          + body.length
                          + "\r\n\r\n"));
          socket.getOutputStream().write(body, 0, 10);
          socket.getOutputStream().flush();
          socket.setSoTimeout(100);
          assertThrows(java.net.SocketTimeoutException.class, () -> socket.getInputStream().read());
          socket.setSoTimeout(3000);
          socket.getOutputStream().write(body, 10, body.length - 10);
          assertEquals(201, readReply(socket.getInputStream()).status());
        });
  }

  @Test
  void servesTwoRequestsOnOneKeepAliveConnection() throws Exception {
    withServer(
        (socket) -> {
          byte[] request = ascii("GET /events/concert-1/seats HTTP/1.1\r\nHost: localhost\r\n\r\n");
          socket.getOutputStream().write(request);
          socket.getOutputStream().write(request);
          Reply first = readReply(socket.getInputStream());
          Reply second = readReply(socket.getInputStream());
          assertEquals(200, first.status());
          assertEquals(200, second.status());
          assertTrue(first.headers().contains("Connection: keep-alive"));
          assertEquals(first.body(), second.body());
        });
  }

  private static void withServer(SocketAction action) throws Exception {
    try (var server = new RawHttpServer(0, TicketingApi.sample())) {
      server.start();
      try (var socket = new Socket("localhost", server.port())) {
        socket.setSoTimeout(3000);
        action.run(socket);
      }
    }
  }

  private static Reply readReply(InputStream in) throws Exception {
    String statusLine = readLine(in);
    var headers = new StringBuilder();
    int length = 0;
    while (true) {
      String header = readLine(in);
      if (header.isEmpty()) {
        break;
      }
      headers.append(header).append('\n');
      if (header.startsWith("Content-Length:")) {
        length = Integer.parseInt(header.substring(15).trim());
      }
    }
    return new Reply(
        Integer.parseInt(statusLine.split(" ")[1]),
        headers.toString(),
        new String(in.readNBytes(length), StandardCharsets.UTF_8));
  }

  private static String readLine(InputStream in) throws Exception {
    var bytes = new ByteArrayOutputStream();
    while (true) {
      int next = in.read();
      if (next == '\r') {
        assertEquals('\n', in.read());
        return bytes.toString(StandardCharsets.ISO_8859_1);
      }
      if (next == -1) throw new java.io.EOFException("Incomplete HTTP reply");
      bytes.write(next);
    }
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private record Reply(int status, String headers, String body) {}

  private interface SocketAction {
    void run(Socket socket) throws Exception;
  }

  @Test
  void oversizedBodyReturnsProblemDetails() throws Exception {
    withServer(
        socket -> {
          socket
              .getOutputStream()
              .write(
                  ascii(
                      "POST /orders HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048577\r\n\r\n"));
          Reply reply = readReply(socket.getInputStream());
          assertEquals(413, reply.status());
          assertTrue(reply.headers().contains("application/problem+json"));
          assertTrue(reply.body().contains("\"status\":413"));
        });
  }
}
