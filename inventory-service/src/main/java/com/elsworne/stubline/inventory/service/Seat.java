package com.elsworne.stubline.inventory.service;

/** A seat stored by the inventory service. */
public record Seat(String id, String eventId, boolean available) {}
