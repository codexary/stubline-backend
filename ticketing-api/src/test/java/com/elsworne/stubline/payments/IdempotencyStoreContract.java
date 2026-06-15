package com.elsworne.stubline.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.elsworne.stubline.http.HttpReply;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class IdempotencyStoreContract {
  protected final Clock clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
  protected IdempotencyStore store;
  private static final byte[] BODY =
      "{\"orderId\":\"o1\",\"amountCents\":500}".getBytes(StandardCharsets.UTF_8);

  abstract IdempotencyStore createStore();

  @BeforeEach
  void setUpStore() {
    store = createStore();
  }

  @Test
  void firstReplayAndMismatch() {
    var provider = new PaymentProviderStub(0);
    var service = new PaymentService(store, provider, clock);
    var first = service.pay("payment", BODY);
    var replay = service.pay("payment", BODY);
    assertEquals(201, first.status());
    assertEquals(first.status(), replay.status());
    assertEquals(first.reason(), replay.reason());
    assertArrayEquals(first.body(), replay.body());
    assertEquals(
        422,
        service
            .pay(
                "payment",
                "{\"orderId\":\"o1\",\"amountCents\":600}".getBytes(StandardCharsets.UTF_8))
            .status());
    assertEquals(1, provider.charges());
  }

  @Test
  void concurrentRequestIsInFlight() throws Exception {
    var provider = new PaymentProviderStub(500);
    var service = new PaymentService(store, provider, clock);
    var first = CompletableFuture.supplyAsync(() -> service.pay("concurrent", BODY));
    assertTrue(provider.awaitStarted());
    assertEquals(409, service.pay("concurrent", BODY).status());
    assertEquals(201, first.get().status());
    assertEquals(1, provider.charges());
  }

  @Test
  void completedRecordExpiresAtBoundary() {
    assertInstanceOf(
        IdempotencyStore.First.class, store.begin("expired", "a".repeat(64), clock.instant()));
    store.complete("expired", "a".repeat(64), HttpReply.json(201, "Created", "{}"));
    assertInstanceOf(
        IdempotencyStore.First.class,
        store.begin("expired", "b".repeat(64), clock.instant().plusSeconds(60)));
  }

  @Test
  void completedRecordDoesNotExpireEarlyAndInFlightNeverExpires() {
    var future = clock.instant().plusSeconds(1);
    store.begin("live", "a".repeat(64), future);
    store.complete("live", "a".repeat(64), HttpReply.json(201, "Created", "{}"));
    assertInstanceOf(IdempotencyStore.Replay.class, store.begin("live", "a".repeat(64), future));
    store.begin("pending", "a".repeat(64), clock.instant().minusSeconds(1));
    assertInstanceOf(
        IdempotencyStore.InFlight.class, store.begin("pending", "a".repeat(64), future));
  }

  @Test
  void preChargeFailureReleasesKeyAndRetryChargesOnce() {
    var provider = new PaymentProviderStub(0);
    provider.failNextCharge();
    var service = new PaymentService(store, provider, clock);
    assertThrows(
        PaymentProviderStub.ChargeNotMadeException.class, () -> service.pay("retry", BODY));
    assertEquals(0, provider.charges());
    var retry = service.pay("retry", BODY);
    assertEquals(201, retry.status());
    assertArrayEquals(retry.body(), service.pay("retry", BODY).body());
    assertEquals(1, provider.charges());
  }

  @Test
  void releasePreservesDifferentHashAndCompletedReply() {
    var future = clock.instant().plusSeconds(60);
    store.begin("guarded", "a".repeat(64), future);
    store.release("guarded", "c".repeat(64));
    assertInstanceOf(
        IdempotencyStore.InFlight.class, store.begin("guarded", "a".repeat(64), future));
    store.complete("guarded", "a".repeat(64), HttpReply.json(201, "Created", "{}"));
    store.release("guarded", "a".repeat(64));
    assertInstanceOf(IdempotencyStore.Replay.class, store.begin("guarded", "a".repeat(64), future));
  }
}
