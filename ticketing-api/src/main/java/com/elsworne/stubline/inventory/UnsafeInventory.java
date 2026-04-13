package com.elsworne.stubline.inventory;

import java.util.concurrent.ConcurrentHashMap;

/** Deliberately broken check-then-act reservation for a race demonstration. */
public final class UnsafeInventory {
  private final ConcurrentHashMap<String, Boolean> seats = new ConcurrentHashMap<>();

  public void add(String seatId) {
    seats.put(seatId, true);
  }

  public boolean reserve(String seatId) {
    if (!Boolean.TRUE.equals(seats.get(seatId))) return false;
    Thread.yield();
    seats.put(seatId, false);
    return true;
  }
}
