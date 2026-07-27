package com.elsworne.stubline.tickets;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.orders.Order;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Fake storage and repository contract for ordinary builds. */
class TicketServiceTest {
  @Test
  void ticketIsStoredUnderOrderKeyAndWrongOrderHasNoUrl() throws Exception {
    var order = new Order("order-1", "concert-1", "A1", Instant.EPOCH);
    var keys = new HashMap<String, String>();
    var repository =
        new TicketRepository() {
          public Optional<Order> find(String id) {
            return id.equals(order.id()) ? Optional.of(order) : Optional.empty();
          }

          public Optional<String> ticketKey(String id) {
            return Optional.ofNullable(keys.get(id));
          }

          public void markTicket(String id, String key) {
            keys.put(id, key);
          }
        };
    var storage =
        new TicketStorage() {
          public void put(String key, byte[] pdf) {
            assertEquals("tickets/order-1.pdf", key);
            assertEquals((byte) 0x25, pdf[0]);
          }

          public URI presignGet(String key, Duration lifetime) {
            assertEquals(Duration.ofMinutes(5), lifetime);
            return URI.create("http://localhost/tickets/order-1.pdf");
          }
        };
    var tickets = new TicketService(repository, storage);
    assertFalse(tickets.downloadUrl("wrong-order").isPresent());
    tickets.issue(order.id());
    assertEquals("tickets/order-1.pdf", keys.get(order.id()));
    assertTrue(tickets.downloadUrl(order.id()).isPresent());
  }
}
