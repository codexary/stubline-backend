package com.elsworne.stubline.http;

import com.elsworne.stubline.inventory.GrpcInventoryClient;
import com.elsworne.stubline.orders.outbox.OrderStore;
import com.elsworne.stubline.orders.outbox.OutboxRelay;
import com.elsworne.stubline.payments.InMemoryIdempotencyStore;
import com.elsworne.stubline.payments.PaymentProviderClient;
import com.elsworne.stubline.payments.PaymentService;
import com.elsworne.stubline.payments.PostgresIdempotencyStore;
import com.elsworne.stubline.tickets.S3TicketStorage;
import com.elsworne.stubline.tickets.TicketService;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the raw or JDK ticketing HTTP server. */
public final class TicketingMain {
  private static final Logger LOG = LoggerFactory.getLogger(TicketingMain.class);

  private TicketingMain() {}

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "raw";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    long lookupDelayMillis = args.length > 4 ? Long.parseLong(args[4]) : 0;
    String jdbcUrl = System.getenv("STUBLINE_JDBC_URL");
    String providerUrl = System.getenv("STUBLINE_PAYMENT_PROVIDER_URL");
    PaymentProviderClient payments =
        providerUrl == null || providerUrl.isBlank()
            ? null
            : new PaymentProviderClient(URI.create(providerUrl));
    String target = System.getenv().getOrDefault("STUBLINE_INVENTORY_TARGET", "localhost:9090");
    GrpcInventoryClient inventory =
        new GrpcInventoryClient(
            NettyChannelBuilder.forTarget(target).usePlaintext().build(), Duration.ofSeconds(2));
    Clock clock = Clock.systemUTC();
    PostgresIdempotencyStore postgresPayments =
        jdbcUrl == null || jdbcUrl.isBlank()
            ? null
            : new PostgresIdempotencyStore(
                jdbcUrl, requireEnv("STUBLINE_DB_USER"), requireEnv("STUBLINE_DB_PASSWORD"), clock);
    PaymentService service =
        payments == null
            ? null
            : new PaymentService(
                postgresPayments == null ? new InMemoryIdempotencyStore(clock) : postgresPayments,
                payments,
                clock);
    OrderStore orderStore =
        jdbcUrl == null || jdbcUrl.isBlank()
            ? null
            : new OrderStore(
                jdbcUrl, requireEnv("STUBLINE_DB_USER"), requireEnv("STUBLINE_DB_PASSWORD"));
    String s3Endpoint = System.getenv("STUBLINE_S3_ENDPOINT");
    S3TicketStorage ticketStorage =
        orderStore == null || System.getenv("STUBLINE_S3_BUCKET") == null
            ? null
            : new S3TicketStorage(
                requireEnv("STUBLINE_S3_BUCKET"),
                System.getenv().getOrDefault("STUBLINE_S3_REGION", "us-east-1"),
                s3Endpoint == null ? null : URI.create(s3Endpoint),
                s3Endpoint == null
                    ? null
                    : URI.create(
                        System.getenv().getOrDefault("STUBLINE_S3_PUBLIC_ENDPOINT", s3Endpoint)),
                Boolean.parseBoolean(
                    System.getenv().getOrDefault("STUBLINE_S3_PATH_STYLE", "true")),
                s3Endpoint == null
                    ? System.getenv("AWS_ACCESS_KEY_ID")
                    : requireEnv("AWS_ACCESS_KEY_ID"),
                s3Endpoint == null
                    ? System.getenv("AWS_SECRET_ACCESS_KEY")
                    : requireEnv("AWS_SECRET_ACCESS_KEY"));
    TicketService tickets =
        ticketStorage == null ? null : new TicketService(orderStore, ticketStorage);
    TicketingApi api =
        new TicketingApi(inventory, lookupDelayMillis, payments, service, orderStore, tickets);
    OutboxRelay relay =
        orderStore == null
            ? null
            : new OutboxRelay(
                orderStore,
                System.getenv().getOrDefault("STUBLINE_KAFKA_BOOTSTRAP", "localhost:9092"));
    var relayWorker =
        relay == null ? null : java.util.concurrent.Executors.newSingleThreadExecutor();
    if (relayWorker != null)
      relayWorker.submit(
          () -> {
            while (!Thread.currentThread().isInterrupted()) {
              try {
                relay.publishBatch();
                Thread.sleep(500);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                LOG.warn("Outbox relay failed; will retry", e);
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }
            }
          });
    ServerExecutor strategy =
        args.length > 2
            ? ServerExecutor.valueOf(args[2].toUpperCase(java.util.Locale.ROOT))
            : ServerExecutor.VIRTUAL;
    int poolSize = args.length > 3 ? Integer.parseInt(args[3]) : 16;
    int adminPort = Integer.parseInt(System.getenv().getOrDefault("STUBLINE_ADMIN_PORT", "8081"));
    try (var admin = new AdminHttpServer(adminPort, api, strategy, poolSize)) {
      admin.start();
      Runtime.getRuntime().addShutdownHook(new Thread(admin::close));
      LOG.info("Admin HTTP server listening on loopback port {}", admin.port());
      if (mode.equals("raw")) {
        try (var server = new RawHttpServer(port, api, strategy, poolSize)) {
          server.start();
          Runtime.getRuntime()
              .addShutdownHook(
                  new Thread(
                      () -> {
                        try {
                          server.close();
                        } catch (Exception ignored) {
                        }
                      }));
          LOG.info("Raw HTTP server listening on {}", server.port());
          new CountDownLatch(1).await();
        }
      } else if (mode.equals("jdk")) {
        try (var server = new JdkHttpServer(port, api, strategy, poolSize)) {
          server.start();
          Runtime.getRuntime().addShutdownHook(new Thread(server::close));
          LOG.info("JDK HTTP server listening on {} with {}", server.port(), strategy);
          new CountDownLatch(1).await();
        }
      } else throw new IllegalArgumentException("Use raw or jdk");
    } finally {
      if (relayWorker != null) relayWorker.shutdownNow();
      if (relay != null) relay.close();
      if (ticketStorage != null) ticketStorage.close();
      if (orderStore != null) orderStore.close();
      inventory.close();
      if (postgresPayments != null) postgresPayments.close();
      if (payments != null) payments.close();
    }
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException(name + " is required when STUBLINE_JDBC_URL is set");
    return value;
  }
}
