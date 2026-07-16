CREATE TABLE IF NOT EXISTS handled_events (event_id TEXT PRIMARY KEY, handled_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS notifications (event_id TEXT PRIMARY KEY REFERENCES handled_events(event_id), order_id TEXT NOT NULL, message TEXT NOT NULL, sent_at TIMESTAMPTZ NOT NULL DEFAULT now());
