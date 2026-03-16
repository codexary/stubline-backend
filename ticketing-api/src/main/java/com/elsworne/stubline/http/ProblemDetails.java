package com.elsworne.stubline.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** RFC 9457 error responses for the public HTTP API. */
public final class ProblemDetails {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ProblemDetails() {}

  public static HttpReply of(int status, String title, String detail, String instance) {
    return of(status, title, detail, instance, Map.of());
  }

  public static HttpReply of(
      int status, String title, String detail, String instance, Map<String, ?> extensions) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("type", "about:blank");
    fields.put("title", title);
    fields.put("status", status);
    fields.put("detail", detail);
    fields.put("instance", instance);
    fields.putAll(extensions);
    try {
      return new HttpReply(
          status,
          title,
          JSON.writeValueAsBytes(fields),
          Map.of("Content-Type", "application/problem+json"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
