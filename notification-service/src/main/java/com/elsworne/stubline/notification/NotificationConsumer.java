package com.elsworne.stubline.notification;

import com.elsworne.stubline.orders.events.ProtobufEventCodec;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Consumer-group worker that commits offsets only after durable handling. */
public final class NotificationConsumer implements AutoCloseable {
  private final KafkaConsumer<String, byte[]> consumer;
  private final NotificationStore store;
  private final ProtobufEventCodec codec = new ProtobufEventCodec();
  private final AtomicLong lag = new AtomicLong();

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
    GlobalOpenTelemetry.getMeter("stubline.notification")
        .gaugeBuilder("stubline.consumer.lag")
        .ofLongs()
        .buildWithCallback(measurement -> measurement.record(lag.get()));
  }

  /** Returns records handled; the hook simulates a crash after handling but before commit. */
  public int pollOnce(Duration timeout, Runnable beforeCommit) throws Exception {
    var records = consumer.poll(timeout);
    long maximumLag =
        consumer.metrics().entrySet().stream()
            .filter(entry -> entry.getKey().name().equals("records-lag-max"))
            .mapToLong(
                entry ->
                    Math.max(
                        0,
                        entry.getValue().metricValue() instanceof Number number
                            ? number.longValue()
                            : 0))
            .max()
            .orElse(0);
    lag.set(maximumLag);
    int count = 0;
    for (var record : records) {
      var event = codec.decode(record.value());
      var span =
          GlobalOpenTelemetry.getTracer("stubline.notification")
              .spanBuilder("notification.handle")
              .setAttribute("order.id", event.orderId())
              .startSpan();
      try (var scope = span.makeCurrent()) {
        store.handle(event);
      } finally {
        span.end();
      }
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
