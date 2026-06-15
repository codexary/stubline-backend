package com.elsworne.stubline.payments;

/** A keyed charge operation whose provider deduplicates successful charges. */
public interface ChargeProvider {
  String charge(String key, String orderId, long amountCents);
}
