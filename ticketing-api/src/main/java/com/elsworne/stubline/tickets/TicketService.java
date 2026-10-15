package com.elsworne.stubline.tickets;

import com.elsworne.stubline.orders.Order;
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

  public boolean ownedBy(String id, String subject) throws SQLException {
    return orders.ownedBy(id, subject);
  }

  public boolean hasOrder(String id) throws SQLException {
    return orders.find(id).isPresent();
  }

  public Optional<Order> findOrder(String id) throws SQLException {
    return orders.find(id);
  }

  public void issue(String id) throws SQLException {
    var order = orders.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown order"));
    String key = "tickets/" + id + ".pdf";
    orders.issueIfAbsent(id, key, () -> storage.put(key, renderer.render(order)));
  }

  public Optional<URI> downloadUrl(String id) throws SQLException {
    return orders.ticketKey(id).map(key -> storage.presignGet(key, Duration.ofMinutes(5)));
  }
}
