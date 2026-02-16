package com.elsworne.stubline.inventory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InMemoryInventoryTest {
  @Test
  void reservesEachSeatOnceAndKeepsEventsSeparate() {
    var inventory = new InMemoryInventory();
    inventory.add(new Seat("A1", "concert", true));
    inventory.add(new Seat("A1", "play", true));
    assertTrue(inventory.reserve("concert", "A1"));
    assertFalse(inventory.reserve("concert", "A1"));
    assertFalse(inventory.reserve("missing", "A1"));
    assertTrue(inventory.seatsFor("play").getFirst().available());
  }
}
