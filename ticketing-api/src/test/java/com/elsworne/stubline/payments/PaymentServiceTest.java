package com.elsworne.stubline.payments;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {
  private static final byte[] BODY =
      "{\"orderId\":\"o1\",\"amountCents\":500}".getBytes(StandardCharsets.UTF_8);

  @Test
  void providerDeduplicatesAfterLocalRecordIsLost() {
    Clock clock = Clock.systemUTC();
    var provider = new PaymentProviderStub(0);
    var first =
        new PaymentService(new InMemoryIdempotencyStore(clock), provider, clock).pay("lost", BODY);
    var retry =
        new PaymentService(new InMemoryIdempotencyStore(clock), provider, clock).pay("lost", BODY);
    assertEquals(201, retry.status());
    assertArrayEquals(first.body(), retry.body());
    assertEquals(1, provider.charges());
    assertThrows(IllegalArgumentException.class, () -> provider.charge("lost", "o1", 600));
    assertEquals(1, provider.charges());
  }

  @Test
  void successfulChargeWithFailedSaveRetainsClaimAndProviderDeduplicatesRecovery() {
    Clock clock = Clock.systemUTC();
    var backing = new InMemoryIdempotencyStore(clock);
    var failing =
        new IdempotencyStore() {
          public Decision begin(String key, String hash, java.time.Instant expiry) {
            return backing.begin(key, hash, expiry);
          }

          public void complete(
              String key, String hash, com.elsworne.stubline.http.HttpReply reply) {
            throw new IllegalStateException("Simulated database outage after charge");
          }

          public void release(String key, String hash) {
            fail("Successful charge must not release the claim");
          }
        };
    var provider = new PaymentProviderStub(0);
    var service = new PaymentService(failing, provider, clock);
    assertThrows(IllegalStateException.class, () -> service.pay("crash", BODY));
    assertEquals(409, service.pay("crash", BODY).status());
    assertEquals(1, provider.charges());
    var recovered = new PaymentService(new InMemoryIdempotencyStore(clock), provider, clock);
    assertEquals(201, recovered.pay("crash", BODY).status());
    assertEquals(1, provider.charges());
  }

  @Test
  void replaysSameResponseAndRejectsChangedBody() {
    Clock clock = Clock.systemUTC();
    var provider = new PaymentProviderStub(0);
    var service = new PaymentService(new InMemoryIdempotencyStore(clock), provider, clock);
    var first = service.pay("same-key", BODY);
    assertEquals(201, first.status());
    assertArrayEquals(first.body(), service.pay("same-key", BODY).body());
    assertEquals(
        422,
        service
            .pay(
                "same-key",
                "{\"orderId\":\"o1\",\"amountCents\":600}".getBytes(StandardCharsets.UTF_8))
            .status());
    assertEquals(1, provider.charges());
    assertEquals(400, service.pay(null, BODY).status());
    assertEquals(
        400,
        service
            .pay(
                "fractional",
                "{\"orderId\":\"o1\",\"amountCents\":1.5}".getBytes(StandardCharsets.UTF_8))
            .status());
  }

  @Test
  void returnsConflictWhileFirstChargeIsRunning() throws Exception {
    Clock clock = Clock.systemUTC();
    var provider = new PaymentProviderStub(300);
    var service = new PaymentService(new InMemoryIdempotencyStore(clock), provider, clock);
    var first = CompletableFuture.supplyAsync(() -> service.pay("concurrent", BODY));
    assertTrue(provider.awaitStarted());
    assertEquals(409, service.pay("concurrent", BODY).status());
    assertEquals(201, first.get().status());
    assertEquals(1, provider.charges());
  }
}
