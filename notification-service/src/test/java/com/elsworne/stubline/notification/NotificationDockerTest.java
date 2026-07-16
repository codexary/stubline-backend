package com.elsworne.stubline.notification;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.orders.Order;
import com.elsworne.stubline.orders.events.OrderPlaced;
import com.elsworne.stubline.orders.events.ProtobufEventCodec;
import com.elsworne.stubline.orders.outbox.OrderStore;
import com.elsworne.stubline.orders.outbox.OutboxRelay;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Kafka and PostgreSQL integration, including a crash before offset commit. */
@Tag("docker")
@EnabledIfSystemProperty(named = "run.docker", matches = "true")
class NotificationDockerTest {
  private static KafkaContainer kafka() throws Exception {
    var kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    kafka.start();
    var props = new Properties();
    props.put("bootstrap.servers", kafka.getBootstrapServers());
    try (var admin = AdminClient.create(props)) {
      admin.createTopics(List.of(new NewTopic("order-placed", 3, (short) 1))).all().get();
    }
    return kafka;
  }

  private static OrderPlaced event() {
    return new OrderPlaced(
        UUID.randomUUID().toString(), "concert", "A1", "buyer", 2500, "AUD", Instant.now(), null);
  }

  private static void send(String bootstrap, OrderPlaced event) throws Exception {
    var props = new Properties();
    props.put("bootstrap.servers", bootstrap);
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", ByteArraySerializer.class.getName());
    try (var producer = new KafkaProducer<String, byte[]>(props)) {
      producer
          .send(
              new ProducerRecord<>(
                  "order-placed", event.concertId(), new ProtobufEventCodec().encode(event)))
          .get();
    }
  }

  private static int receive(NotificationConsumer consumer) throws Exception {
    for (int i = 0; i < 20; i++) {
      int count = consumer.pollOnce(Duration.ofMillis(500), () -> {});
      if (count > 0) return count;
    }
    return 0;
  }

  @Test
  void outboxRelayPublishesStoredOrder() throws Exception {
    try (var postgres =
            new PostgreSQLContainer<>("postgres:18.6-alpine3.24")
                .withDatabaseName("ticketing")
                .withUsername("ticketing");
        var kafka = kafka()) {
      postgres.start();
      try (var connection =
              java.sql.DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("CREATE ROLE notifications LOGIN PASSWORD 'notifications'");
        statement.execute("CREATE DATABASE notifications OWNER notifications");
      }
      String notificationsUrl = postgres.getJdbcUrl().replace("/ticketing", "/notifications");
      var event = event();
      try (var orders =
              new OrderStore(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var relay = new OutboxRelay(orders, kafka.getBootstrapServers());
          var notifications =
              new NotificationStore(notificationsUrl, "notifications", "notifications");
          var consumer =
              new NotificationConsumer(kafka.getBootstrapServers(), "relay-test", notifications)) {
        orders.save(
            new Order(event.orderId(), event.concertId(), event.seatId(), event.occurredAt()),
            event.buyer(),
            event.amountMinor(),
            event.currency());
        assertEquals(1, relay.publishBatch());
        assertEquals(0, relay.publishBatch());
        assertEquals(1, receive(consumer));
        assertEquals(1, notifications.count());
      }
    }
  }

  @Test
  void distinctMessagesForSameOrderAreHandledOnceEach() throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:18.6-alpine3.24")) {
      postgres.start();
      try (var notifications =
          new NotificationStore(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        var first =
            new OrderPlaced(
                "order-1", "concert", "A1", "buyer", 2500, "AUD", Instant.EPOCH, null, "message-1");
        var second =
            new OrderPlaced(
                "order-1", "concert", "A1", "buyer", 2500, "AUD", Instant.EPOCH, null, "message-2");
        assertTrue(notifications.handle(first));
        assertFalse(notifications.handle(first));
        assertTrue(notifications.handle(second));
        assertEquals(2, notifications.count());
      }
    }
  }

  @Test
  void consumerHandlesOnce() throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:18.6-alpine3.24");
        var kafka = kafka()) {
      postgres.start();
      send(kafka.getBootstrapServers(), event());
      try (var notifications =
              new NotificationStore(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var consumer =
              new NotificationConsumer(
                  kafka.getBootstrapServers(), "consumer-test", notifications)) {
        assertEquals(1, receive(consumer));
        assertEquals(1, notifications.count());
      }
    }
  }

  @Test
  void redeliveryAfterHandlingBeforeCommitIsDeduplicated() throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:18.6-alpine3.24");
        var kafka = kafka()) {
      postgres.start();
      send(kafka.getBootstrapServers(), event());
      try (var notifications =
          new NotificationStore(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        try (var first =
            new NotificationConsumer(
                kafka.getBootstrapServers(), "redelivery-test", notifications)) {
          boolean crashed = false;
          for (int i = 0; i < 20 && !crashed; i++) {
            try {
              first.pollOnce(
                  Duration.ofMillis(500),
                  () -> {
                    throw new SimulatedCrash();
                  });
            } catch (SimulatedCrash e) {
              crashed = true;
            }
          }
          assertTrue(crashed);
          assertEquals(1, notifications.count());
        }
        try (var restarted =
            new NotificationConsumer(
                kafka.getBootstrapServers(), "redelivery-test", notifications)) {
          assertEquals(1, receive(restarted));
          assertEquals(1, notifications.count());
        }
      }
    }
  }

  private static final class SimulatedCrash extends RuntimeException {}
}
