package com.elsworne.stubline.orders.events;

import java.io.IOException;

/** Encodes the same event contract in a chosen wire format. */
public interface EventCodec {
  byte[] encode(OrderPlaced event) throws IOException;

  OrderPlaced decode(byte[] encoded) throws IOException;
}
