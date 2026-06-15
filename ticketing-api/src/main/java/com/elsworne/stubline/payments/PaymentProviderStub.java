package com.elsworne.stubline.payments;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Models a provider retaining keyed charges independently of our request history. */
public final class PaymentProviderStub implements ChargeProvider {
  private final Map<String, Charge> charges = new HashMap<>();
  private final CountDownLatch started = new CountDownLatch(1);
  private final long delayMillis;
  private boolean failNext;

  public PaymentProviderStub(long delayMillis) {
    this.delayMillis = delayMillis;
  }

  public synchronized int charges() {
    return charges.size();
  }

  boolean awaitStarted() throws InterruptedException {
    return started.await(3, TimeUnit.SECONDS);
  }

  /** Injects a definitive rejection before the next new charge moves money. */
  public synchronized void failNextCharge() {
    failNext = true;
  }

  public synchronized String charge(String key, String orderId, long amountCents) {
    Charge existing = charges.get(key);
    if (existing != null) {
      if (!existing.orderId().equals(orderId) || existing.amountCents() != amountCents) {
        throw new IllegalArgumentException("Provider key reused with different payment");
      }
      return existing.id();
    }
    started.countDown();
    if (failNext) {
      failNext = false;
      throw new ChargeNotMadeException("Provider rejected charge before moving money");
    }
    if (delayMillis > 0) {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ChargeNotMadeException("Charge interrupted before moving money");
      }
    }
    String id = "ch_" + (charges.size() + 1);
    charges.put(key, new Charge(orderId, amountCents, id));
    return id;
  }

  private record Charge(String orderId, long amountCents, String id) {}

  /** Signals a definitive pre-charge failure, never a timeout with an unknown outcome. */
  public static final class ChargeNotMadeException extends RuntimeException {
    public ChargeNotMadeException(String message) {
      super(message);
    }
  }
}
