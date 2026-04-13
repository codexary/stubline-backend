package com.elsworne.stubline.inventory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

public class ReservationRaceTest {
  @Test
  void unsafeCheckThenActCanOversellWithinBoundedRounds() throws Exception {
    boolean observed = false;
    for (int round = 0; round < 100 && !observed; round++) {
      var unsafe = new UnsafeInventory();
      unsafe.add("A1");
      observed = winners(() -> unsafe.reserve("A1")) > 1;
    }
    assertTrue(observed, "Expected the demonstration race within 100 rounds");
  }

  @Test
  void synchronizedInventoryAdmitsOneBuyer() throws Exception {
    var safe = new InMemoryInventory();
    safe.add(new Seat("A1", "concert", true));
    assertEquals(1, winners(() -> safe.reserve("concert", "A1")));
  }

  public interface Reservation {
    boolean reserve() throws Exception;
  }

  public static int winners(Reservation reservation) throws Exception {
    try (var workers = Executors.newFixedThreadPool(32)) {
      var start = new CountDownLatch(1);
      var ready = new CountDownLatch(32);
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 32; i++)
        futures.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return reservation.reserve();
                }));
      ready.await();
      start.countDown();
      int wins = 0;
      for (var future : futures) if (future.get()) wins++;
      return wins;
    }
  }
}
