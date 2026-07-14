package com.elsworne.stubline.orders.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Publishes committed outbox rows and marks them sent; retry may duplicate a message. */
public final class OutboxRelay implements AutoCloseable {
  private final OrderStore store;
  private final KafkaProducer<String, byte[]> producer;

  public OutboxRelay(OrderStore store, String bootstrap) {
    this.store = store;
    var props = new Properties();
    props.put("bootstrap.servers", bootstrap);
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", ByteArraySerializer.class.getName());
    props.put("acks", "all");
    props.put("enable.idempotence", "true");
    try (var admin = AdminClient.create(props)) {
      if (!admin.listTopics().names().get().contains("order-placed")) {
        try {
          admin.createTopics(List.of(new NewTopic("order-placed", 3, (short) 1))).all().get();
        } catch (java.util.concurrent.ExecutionException e) {
          if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException))
            throw e;
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot initialize order topic", e);
    }
    producer = new KafkaProducer<>(props);
  }

  public int publishBatch() throws Exception {
    try (var connection = store.dataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var select =
          connection.prepareStatement(
              "SELECT id, event_id, payload FROM order_outbox WHERE published_at IS NULL ORDER BY created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED")) {
        int count = 0;
        try (var rows = select.executeQuery()) {
          while (rows.next()) {
            String id = rows.getString(1);
            producer
                .send(new ProducerRecord<>("order-placed", rows.getString(2), rows.getBytes(3)))
                .get();
            try (var update =
                connection.prepareStatement(
                    "UPDATE order_outbox SET published_at = now() WHERE id = ?")) {
              update.setString(1, id);
              update.executeUpdate();
            }
            count++;
          }
        }
        connection.commit();
        return count;
      } catch (Exception e) {
        connection.rollback();
        throw e;
      }
    }
  }

  @Override
  public void close() {
    producer.close(Duration.ofSeconds(2));
  }
}
