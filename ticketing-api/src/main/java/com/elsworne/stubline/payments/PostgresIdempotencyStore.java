package com.elsworne.stubline.payments;

import com.elsworne.stubline.http.HttpReply;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Persists payment request decisions in PostgreSQL. */
public final class PostgresIdempotencyStore implements IdempotencyStore, AutoCloseable {
  private final DataSource pool;
  private final boolean ownsPool;
  private final Clock clock;

  public PostgresIdempotencyStore(String url, String user, String password, Clock clock) {
    this.clock = Objects.requireNonNull(clock);
    var config = new HikariConfig();
    config.setJdbcUrl(Objects.requireNonNull(url));
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(3000);
    config.setPoolName("payment-idempotency");
    pool = new HikariDataSource(config);
    ownsPool = true;
    initialize();
  }

  public PostgresIdempotencyStore(DataSource pool, Clock clock) {
    this.pool = Objects.requireNonNull(pool);
    this.clock = Objects.requireNonNull(clock);
    this.ownsPool = false;
  }

  public void initialize() {
    Flyway.configure()
        .dataSource(pool)
        .locations("classpath:db/payment", "classpath:db/orders")
        .table("ticketing_flyway_history")
        .load()
        .migrate();
  }

  @Override
  public Decision begin(String key, String hash, Instant expiresAt) {
    try (Connection connection = connect()) {
      // Only completed rows expire automatically; an in-flight charge must not be claimed again.
      try (var delete =
          connection.prepareStatement(
              "DELETE FROM payment_idempotency WHERE idempotency_key = ? AND state = 'COMPLETE' AND expires_at <= ?")) {
        delete.setString(1, key);
        delete.setObject(2, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        delete.executeUpdate();
      }
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO payment_idempotency (idempotency_key, body_hash, state, expires_at) VALUES (?, ?, 'IN_FLIGHT', ?) ON CONFLICT DO NOTHING")) {
        insert.setString(1, key);
        insert.setString(2, hash);
        insert.setObject(3, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        if (insert.executeUpdate() == 1) return new First();
      }
      try (var select =
          connection.prepareStatement(
              "SELECT body_hash, state, response_status, response_reason, response_body FROM payment_idempotency WHERE idempotency_key = ?")) {
        select.setString(1, key);
        try (var result = select.executeQuery()) {
          if (!result.next()) throw new IllegalStateException("Idempotency row disappeared");
          if (!hash.equals(result.getString(1))) return new Mismatch();
          if ("IN_FLIGHT".equals(result.getString(2))) return new InFlight();
          return new Replay(
              new HttpReply(result.getInt(3), result.getString(4), result.getBytes(5)));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot claim payment key", e);
    }
  }

  @Override
  public void complete(String key, String hash, HttpReply reply) {
    try (Connection connection = connect();
        var update =
            connection.prepareStatement(
                "UPDATE payment_idempotency SET state = 'COMPLETE', response_status = ?, response_reason = ?, response_body = ? WHERE idempotency_key = ? AND body_hash = ? AND state = 'IN_FLIGHT'")) {
      update.setInt(1, reply.status());
      update.setString(2, reply.reason());
      update.setBytes(3, reply.body());
      update.setString(4, key);
      update.setString(5, hash);
      if (update.executeUpdate() != 1)
        throw new IllegalStateException("No matching in-flight payment");
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot complete payment key", e);
    }
  }

  @Override
  public void release(String key, String hash) {
    try (Connection connection = connect();
        var delete =
            connection.prepareStatement(
                "DELETE FROM payment_idempotency WHERE idempotency_key = ? AND body_hash = ? AND state = 'IN_FLIGHT'")) {
      delete.setString(1, key);
      delete.setString(2, hash);
      delete.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot release payment key", e);
    }
  }

  private Connection connect() throws SQLException {
    return pool.getConnection();
  }

  @Override
  public boolean ready() {
    try (var connection = pool.getConnection()) {
      return connection.isValid(2);
    } catch (java.sql.SQLException e) {
      return false;
    }
  }

  @Override
  public void close() {
    if (ownsPool) ((HikariDataSource) pool).close();
  }
}
