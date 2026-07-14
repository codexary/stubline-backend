package com.elsworne.stubline.orders.outbox;

import com.elsworne.stubline.orders.Order;
import com.elsworne.stubline.orders.events.OrderPlaced;
import com.elsworne.stubline.orders.events.ProtobufEventCodec;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Atomically inserts an order and its encoded outbox event. */
public final class OrderStore implements AutoCloseable {
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
                "INSERT INTO orders(id, event_id, seat_id, created_at) VALUES (?, ?, ?, ?)")) {
          insert.setString(1, order.id());
          insert.setString(2, order.eventId());
          insert.setString(3, order.seatId());
          insert.setObject(
              4, java.time.OffsetDateTime.ofInstant(order.createdAt(), java.time.ZoneOffset.UTC));
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
  public void close() {
    pool.close();
  }
}
