package com.elsworne.stubline.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A local seat inventory used before persistent storage is introduced. */
public final class InMemoryInventory implements SeatInventory {
  private final Map<String, Map<String, Seat>> events = new LinkedHashMap<>();

  public synchronized void add(Seat seat) {
    Map<String, Seat> seats =
        events.computeIfAbsent(seat.eventId(), ignored -> new LinkedHashMap<>());
    if (seats.putIfAbsent(seat.id(), seat) != null)
      throw new IllegalArgumentException("Duplicate seat");
  }

  public synchronized List<Seat> seatsFor(String eventId) {
    return List.copyOf(events.getOrDefault(eventId, Map.of()).values());
  }

  public synchronized boolean reserve(String eventId, String seatId) {
    Map<String, Seat> seats = events.get(eventId);
    if (seats == null) return false;
    Seat seat = seats.get(seatId);
    if (seat == null || !seat.available()) return false;
    seats.put(seatId, new Seat(seat.id(), seat.eventId(), false));
    return true;
  }
}
