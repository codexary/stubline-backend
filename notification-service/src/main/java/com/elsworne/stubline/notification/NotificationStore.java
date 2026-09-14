package com.elsworne.stubline.notification;

import com.elsworne.stubline.orders.events.OrderPlaced;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deduplicates handled order events and records log-line notifications atomically. */
public final class NotificationStore implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationStore.class);
  private final HikariDataSource pool;

  public NotificationStore(String url, String user, String password) {
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(4);
    pool = new HikariDataSource(config);
    Flyway.configure()
        .dataSource(pool)
        .locations("classpath:db/notification")
        .table("notification_flyway_history")
        .load()
        .migrate();
  }

  public boolean handle(OrderPlaced event) throws SQLException {
    try (var connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO handled_events(event_id) VALUES (?) ON CONFLICT DO NOTHING")) {
          insert.setString(1, event.eventId());
          if (insert.executeUpdate() == 0) {
            LOG.info("Duplicate notification dropped for event {}", event.eventId());
            connection.commit();
            return false;
          }
        }
        String message = "e-ticket for order " + event.orderId() + " seat " + event.seatId();
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO notifications(event_id, order_id, message) VALUES (?, ?, ?)")) {
          insert.setString(1, event.eventId());
          insert.setString(2, event.orderId());
          insert.setString(3, message);
          insert.executeUpdate();
        }
        connection.commit();
        LOG.info("Sending {}", message);
        return true;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  public int count() throws SQLException {
    try (var connection = pool.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM notifications")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  public boolean ready() {
    try (var connection = pool.getConnection()) {
      return connection.isValid(2);
    } catch (SQLException e) {
      return false;
    }
  }

  @Override
  public void close() {
    pool.close();
  }
}
