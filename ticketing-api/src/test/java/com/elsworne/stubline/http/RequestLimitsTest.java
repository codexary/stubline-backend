package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestLimitsTest {
  @Test
  void rawRejectsOversizedHeader() throws Exception {
    try (var server = new RawHttpServer(0, TicketingApi.sample())) {
      server.start();
      assertTrue(
          request(
                  server.port(),
                  "GET /events/concert-1/seats HTTP/1.1\r\nHost: localhost\r\nX-Fill: "
                      + "a".repeat(8200)
                      + "\r\n\r\n")
              .startsWith("HTTP/1.1 431"));
    }
  }

  @Test
  void rawRejectsOversizedBody() throws Exception {
    try (var server = new RawHttpServer(0, TicketingApi.sample())) {
      server.start();
      assertTrue(
          request(
                  server.port(),
                  "POST /orders HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048577\r\n\r\n")
              .startsWith("HTTP/1.1 413"));
    }
  }

  @Test
  void jdkRejectsOversizedHeader() throws Exception {
    try (var server = new JdkHttpServer(0, TicketingApi.sample())) {
      server.start();
      assertTrue(
          request(
                  server.port(),
                  "GET /events/concert-1/seats HTTP/1.1\r\nHost: localhost\r\nX-Fill: "
                      + "a".repeat(8200)
                      + "\r\n\r\n")
              .startsWith("HTTP/1.1 431"));
    }
  }

  @Test
  void rawTimesOutSlowHeaders() throws Exception {
    try (var server = new RawHttpServer(0, TicketingApi.sample())) {
      server.start();
      assertTrue(
          request(server.port(), "GET /events/concert-1/seats HTTP/1.1\r\nHost: local")
              .startsWith("HTTP/1.1 408"));
    }
  }

  @Test
  void publicListenerHidesDiagnostics() throws Exception {
    var api = TicketingApi.sample();
    try (var publicServer = new JdkHttpServer(0, api);
        var admin = new AdminHttpServer(0, api, ServerExecutor.VIRTUAL, 2)) {
      publicServer.start();
      admin.start();
      for (String path :
          new String[] {
            "/diagnostics/thread", "/diagnostics/runtime", "/diagnostics/dns?name=localhost"
          }) {
        assertTrue(
            request(publicServer.port(), "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .startsWith("HTTP/1.1 404"));
        assertEquals(
            404,
            api.handle(
                    new HttpRequest(
                        "GET", path.split("\\?", 2)[0], java.util.Map.of(), new byte[0]))
                .status());
      }
      assertTrue(
          request(admin.port(), "GET /diagnostics/thread HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .startsWith("HTTP/1.1 200"));
    }
  }

  private static String request(int port, String request) throws Exception {
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(8000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      var bytes = new ByteArrayOutputStream();
      byte[] chunk = new byte[1024];
      int count;
      while ((count = socket.getInputStream().read(chunk)) > 0) {
        bytes.write(chunk, 0, count);
        if (bytes.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) break;
      }
      return bytes.toString(StandardCharsets.ISO_8859_1);
    }
  }
}
