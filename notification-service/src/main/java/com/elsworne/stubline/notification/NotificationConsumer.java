package com.elsworne.stubline.notification;

import com.elsworne.stubline.orders.events.ProtobufEventCodec;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Consumer-group worker that commits offsets only after durable handling. */
public final class NotificationConsumer implements AutoCloseable {
  private final KafkaConsumer<String, byte[]> consumer;
  private final NotificationStore store;
  private final ProtobufEventCodec codec = new ProtobufEventCodec();

  public NotificationConsumer(String bootstrap, String group, NotificationStore store) {
    this.store = store;
    var props = new Properties();
    props.put("bootstrap.servers", bootstrap);
    props.put("group.id", group);
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", ByteArrayDeserializer.class.getName());
    props.put("enable.auto.commit", "false");
    props.put("auto.offset.reset", "earliest");
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of("order-placed"));
  }

  /** Returns records handled; the hook simulates a crash after handling but before commit. */
  public int pollOnce(Duration timeout, Runnable beforeCommit) throws Exception {
    var records = consumer.poll(timeout);
    int count = 0;
    for (var record : records) {
      store.handle(codec.decode(record.value()));
      count++;
    }
    if (count > 0) {
      beforeCommit.run();
      consumer.commitSync();
    }
    return count;
  }

  @Override
  public void close() {
    consumer.close();
  }
}
