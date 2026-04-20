CREATE TABLE seats (
  event_id TEXT NOT NULL,
  seat_id TEXT NOT NULL,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (event_id, seat_id)
);
