package com.elsworne.stubline.orders.outbox;

import com.elsworne.stubline.orders.Order;
import com.elsworne.stubline.orders.events.OrderPlaced;
import com.elsworne.stubline.orders.events.ProtobufEventCodec;
import com.elsworne.stubline.tickets.TicketRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Atomically inserts an order and its encoded outbox event. */
public final class OrderStore implements TicketRepository, AutoCloseable {
  private final HikariDataSource pool;
  private final ProtobufEventCodec codec = new ProtobufEventCodec();

  public OrderStore(String url, String user, String password) {
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(8);
    config.setConnectionTimeout(3000);
    pool = new HikariDataSource(config);
    Flyway.configure()
        .dataSource(pool)
        .locations("classpath:db/payment", "classpath:db/orders")
        .table("ticketing_flyway_history")
        .load()
        .migrate();
  }

  public DataSource dataSource() {
    return pool;
  }

  public void save(Order order, String buyer, long amountMinor, String currency)
      throws SQLException {
    save(order, buyer, amountMinor, currency, null);
  }

  public void save(Order order, String buyer, long amountMinor, String currency, String buyerSub)
      throws SQLException {
    var event =
        new OrderPlaced(
            order.id(),
            order.eventId(),
            order.seatId(),
            buyer,
            amountMinor,
            currency,
            order.createdAt(),
            null,
            java.util.UUID.randomUUID().toString());
    try (var connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO orders(id, event_id, seat_id, created_at, buyer_sub) VALUES (?, ?, ?, ?, ?)")) {
          insert.setString(1, order.id());
          insert.setString(2, order.eventId());
          insert.setString(3, order.seatId());
          insert.setObject(
              4, java.time.OffsetDateTime.ofInstant(order.createdAt(), java.time.ZoneOffset.UTC));
          insert.setString(5, buyerSub);
          insert.executeUpdate();
        }
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO order_outbox(id, event_id, payload, created_at) VALUES (?, ?, ?, ?)")) {
          insert.setString(1, order.id());
          insert.setString(2, order.eventId());
          insert.setBytes(3, codec.encode(event));
          insert.setObject(
              4, java.time.OffsetDateTime.ofInstant(order.createdAt(), java.time.ZoneOffset.UTC));
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  @Override
  public boolean ownedBy(String id, String subject) throws SQLException {
    try (var connection = pool.getConnection();
        var select = connection.prepareStatement("SELECT buyer_sub FROM orders WHERE id = ?")) {
      select.setString(1, id);
      try (var rows = select.executeQuery()) {
        return rows.next() && subject.equals(rows.getString(1));
      }
    }
  }

  public Optional<Order> find(String id) throws SQLException {
    try (var connection = pool.getConnection();
        var select =
            connection.prepareStatement(
                "SELECT event_id, seat_id, created_at FROM orders WHERE id = ?")) {
      select.setString(1, id);
      try (var rows = select.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(
            new Order(
                id,
                rows.getString(1),
                rows.getString(2),
                rows.getObject(3, OffsetDateTime.class).toInstant()));
      }
    }
  }

  public Optional<String> ticketKey(String id) throws SQLException {
    try (var connection = pool.getConnection();
        var select =
            connection.prepareStatement("SELECT ticket_object_key FROM orders WHERE id = ?")) {
      select.setString(1, id);
      try (var rows = select.executeQuery()) {
        return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
      }
    }
  }

  public void markTicket(String id, String key) throws SQLException {
    try (var connection = pool.getConnection();
        var update =
            connection.prepareStatement("UPDATE orders SET ticket_object_key = ? WHERE id = ?")) {
      update.setString(1, key);
      update.setString(2, id);
      if (update.executeUpdate() != 1) throw new SQLException("Order not found: " + id);
    }
  }

  @Override
  public void issueIfAbsent(String id, String key, Runnable upload) throws SQLException {
    try (var connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var select =
            connection.prepareStatement(
                "SELECT ticket_object_key FROM orders WHERE id = ? FOR UPDATE")) {
          select.setString(1, id);
          try (var rows = select.executeQuery()) {
            if (!rows.next()) throw new SQLException("Order not found: " + id);
            if (rows.getString(1) != null) {
              connection.commit();
              return;
            }
          }
        }
        upload.run();
        try (var update =
            connection.prepareStatement(
                "UPDATE orders SET ticket_object_key = ? WHERE id = ? AND ticket_object_key IS NULL")) {
          update.setString(1, key);
          update.setString(2, id);
          if (update.executeUpdate() != 1) throw new SQLException("Ticket issue claim lost: " + id);
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  @Override
  public void close() {
    pool.close();
  }
}
