package com.elsworne.stubline.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteTemplateTest {
  @Test
  void reportsBoundedTemplatesForMatchedRoutes() {
    assertEquals("/events", template("GET", "/events"));
    assertEquals("/events/{eventId}/seats", template("GET", "/events/concert-1/seats"));
    assertEquals("/events/{eventId}/seats", template("GET", "/events/another/seats"));
    assertEquals("/orders", template("POST", "/orders"));
    assertEquals("/orders/{orderId}", template("GET", "/orders/unique-order-id"));
    assertEquals("/orders/{orderId}/ticket", template("GET", "/orders/unique-order-id/ticket"));
    assertEquals("/payments", template("POST", "/payments"));
    assertEquals("/health/ready", template("GET", "/health/ready"));
    assertNull(template("GET", "/events/unique-id/unknown"));
  }

  private static String template(String method, String path) {
    return TicketingApi.routeTemplate(new HttpRequest(method, path, Map.of(), new byte[0]));
  }
}
