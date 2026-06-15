package com.elsworne.stubline.payments;

class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {
  @Override
  IdempotencyStore createStore() {
    return new InMemoryIdempotencyStore(clock);
  }
}
