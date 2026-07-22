package com.elsworne.stubline.tickets;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/** Issues a ticket after payment and delegates downloads with signed URLs. */
public final class TicketService {
  private final TicketRepository orders;
  private final TicketStorage storage;
  private final TicketRenderer renderer = new TicketRenderer();

  public TicketService(TicketRepository orders, TicketStorage storage) {
    this.orders = orders;
    this.storage = storage;
  }

  public boolean hasOrder(String id) throws SQLException {
    return orders.find(id).isPresent();
  }

  public void issue(String id) throws SQLException {
    var order = orders.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown order"));
    if (orders.ticketKey(id).isPresent()) return;
    String key = "tickets/" + id + ".pdf";
    storage.put(key, renderer.render(order));
    orders.markTicket(id, key);
  }

  public Optional<URI> downloadUrl(String id) throws SQLException {
    return orders.ticketKey(id).map(key -> storage.presignGet(key, Duration.ofMinutes(5)));
  }
}
