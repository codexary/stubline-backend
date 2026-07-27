package com.elsworne.stubline.tickets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.elsworne.stubline.http.HttpRequest;
import com.elsworne.stubline.http.TicketingApi;
import com.elsworne.stubline.inventory.InMemoryInventory;
import com.elsworne.stubline.orders.Order;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Checks ticket route lookup without infrastructure. */
class TicketRouteTest {
  @Test
  void wrongOrderIs404AndIssuedOrderGetsSignedUrl() throws Exception {
    var order = new Order("order-1", "concert", "A1", Instant.EPOCH);
    var repository =
        new TicketRepository() {
          private String key;

          public Optional<Order> find(String id) {
            return id.equals(order.id()) ? Optional.of(order) : Optional.empty();
          }

          public Optional<String> ticketKey(String id) {
            return id.equals(order.id()) ? Optional.ofNullable(key) : Optional.empty();
          }

          public void markTicket(String id, String value) {
            key = value;
          }
        };
    var storage =
        new TicketStorage() {
          public void put(String key, byte[] pdf) {}

          public URI presignGet(String key, Duration lifetime) {
            return URI.create("http://localhost:8333/issued");
          }
        };
    var tickets = new TicketService(repository, storage);
    var api = new TicketingApi(new InMemoryInventory(), 0, null, null, null, tickets);
    assertEquals(
        404,
        api.handle(new HttpRequest("GET", "/orders/wrong/ticket", Map.of(), new byte[0])).status());
    tickets.issue(order.id());
    var response =
        api.handle(new HttpRequest("GET", "/orders/order-1/ticket", Map.of(), new byte[0]));
    assertEquals(200, response.status());
    assertTrue(new String(response.body()).contains("http://localhost:8333/issued"));
  }
}
