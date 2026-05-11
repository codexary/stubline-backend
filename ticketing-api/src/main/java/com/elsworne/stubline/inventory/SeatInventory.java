package com.elsworne.stubline.inventory;

import java.sql.SQLException;
import java.util.List;

/** Seat lookup and reservation contract shared by local and persistent inventories. */
public interface SeatInventory {
  List<Seat> seatsFor(String eventId) throws SQLException;

  default boolean ready() {
    return true;
  }

  boolean reserve(String eventId, String seatId) throws SQLException;
}
