CREATE TABLE IF NOT EXISTS orders (
  id TEXT PRIMARY KEY, event_id TEXT NOT NULL, seat_id TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS order_outbox (
  id TEXT PRIMARY KEY, event_id TEXT NOT NULL, payload BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS order_outbox_pending ON order_outbox(created_at) WHERE published_at IS NULL;
