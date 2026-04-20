package com.elsworne.stubline.http;

import java.util.Map;

/** A parsed request passed from either HTTP server to the ticketing API. */
public record HttpRequest(
    String method, String path, Map<String, String> headers, byte[] body, String query) {
  public HttpRequest(String method, String path, Map<String, String> headers, byte[] body) {
    this(method, path, headers, body, null);
  }

  public String header(String name) {
    return headers.get(name.toLowerCase(java.util.Locale.ROOT));
  }
}
