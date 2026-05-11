package com.elsworne.stubline.inventory.pg;

import com.elsworne.stubline.inventory.Seat;
import com.elsworne.stubline.inventory.SeatInventory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;

/** PostgreSQL-backed seat reservations with row locks or optimistic versioning. */
public final class PostgresInventory implements SeatInventory, AutoCloseable {
  public enum Strategy {
    ROW_LOCK,
    OPTIMISTIC
  }

  private final HikariDataSource pool;
  private final Strategy strategy;
  private final AtomicLong retries = new AtomicLong();
  private final AtomicLong versionConflicts = new AtomicLong();

  public PostgresInventory(String url, String user, String password, Strategy strategy) {
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(20);
    config.setConnectionTimeout(5000);
    pool = new HikariDataSource(config);
    Flyway.configure().dataSource(pool).load().migrate();
    this.strategy = strategy;
  }

  public void add(String eventId, String seatId) throws SQLException {
    try (var connection = pool.getConnection();
        var insert =
            connection.prepareStatement(
                "INSERT INTO seats(event_id, seat_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
      insert.setString(1, eventId);
      insert.setString(2, seatId);
      insert.executeUpdate();
    }
  }

  @Override
  public java.util.List<Seat> seatsFor(String eventId) throws SQLException {
    var seats = new java.util.ArrayList<Seat>();
    try (var connection = pool.getConnection();
        var select =
            connection.prepareStatement(
                "SELECT seat_id, available FROM seats WHERE event_id = ? ORDER BY seat_id")) {
      select.setString(1, eventId);
      try (var rows = select.executeQuery()) {
        while (rows.next()) seats.add(new Seat(rows.getString(1), eventId, rows.getBoolean(2)));
      }
    }
    return java.util.List.copyOf(seats);
  }

  @Override
  public boolean reserve(String eventId, String seatId) throws SQLException {
    return strategy == Strategy.ROW_LOCK
        ? reserveWithLock(eventId, seatId)
        : reserveOptimistically(eventId, seatId);
  }

  private boolean reserveWithLock(String eventId, String seatId) throws SQLException {
    try (var connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var select =
            connection.prepareStatement(
                "SELECT available FROM seats WHERE event_id = ? AND seat_id = ? FOR UPDATE")) {
          select.setString(1, eventId);
          select.setString(2, seatId);
          try (var rows = select.executeQuery()) {
            if (!rows.next() || !rows.getBoolean(1)) {
              connection.rollback();
              return false;
            }
          }
        }
        try (var update =
            connection.prepareStatement(
                "UPDATE seats SET available = FALSE, version = version + 1 WHERE event_id = ? AND seat_id = ?")) {
          update.setString(1, eventId);
          update.setString(2, seatId);
          update.executeUpdate();
        }
        connection.commit();
        return true;
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  private boolean reserveOptimistically(String eventId, String seatId) throws SQLException {
    for (int attempt = 0; attempt < 100; attempt++) {
      try (var connection = pool.getConnection()) {
        long version;
        try (var select =
            connection.prepareStatement(
                "SELECT available, version FROM seats WHERE event_id = ? AND seat_id = ?")) {
          select.setString(1, eventId);
          select.setString(2, seatId);
          try (var rows = select.executeQuery()) {
            if (!rows.next() || !rows.getBoolean(1)) return false;
            version = rows.getLong(2);
          }
        }
        try (var update =
            connection.prepareStatement(
                "UPDATE seats SET available = FALSE, version = version + 1 WHERE event_id = ? AND seat_id = ? AND available = TRUE AND version = ?")) {
          update.setString(1, eventId);
          update.setString(2, seatId);
          update.setLong(3, version);
          if (update.executeUpdate() == 1) return true;
          versionConflicts.incrementAndGet();
          if (attempt + 1 < 100) retries.incrementAndGet();
        }
      }
    }
    throw new SQLException("Optimistic reservation exceeded retry budget");
  }

  public long versionConflicts() {
    return versionConflicts.get();
  }

  public long retries() {
    return retries.get();
  }

  @Override
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
