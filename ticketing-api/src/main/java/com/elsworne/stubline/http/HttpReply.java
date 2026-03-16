package com.elsworne.stubline.http;

import java.nio.charset.StandardCharsets;

/** An HTTP status and body returned by the ticketing API. */
public record HttpReply(
    int status, String reason, byte[] body, java.util.Map<String, String> headers) {
  public HttpReply(int status, String reason, byte[] body) {
    this(status, reason, body, java.util.Map.of());
  }

  public static HttpReply json(int status, String reason, String json) {
    return new HttpReply(status, reason, json.getBytes(StandardCharsets.UTF_8));
  }
}
