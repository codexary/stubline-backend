package com.elsworne.stubline.tickets;

import com.elsworne.stubline.orders.Order;
import java.sql.SQLException;
import java.util.Optional;

/** Order data needed to issue and locate a ticket. */
public interface TicketRepository {
  Optional<Order> find(String id) throws SQLException;

  default boolean ownedBy(String id, String subject) throws SQLException {
    return false;
  }

  Optional<String> ticketKey(String id) throws SQLException;

  void markTicket(String id, String key) throws SQLException;

  default void issueIfAbsent(String id, String key, Runnable upload) throws SQLException {
    if (ticketKey(id).isPresent()) return;
    upload.run();
    markTicket(id, key);
  }
}
