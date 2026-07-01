CREATE TABLE IF NOT EXISTS payment_idempotency (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    body_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('IN_FLIGHT', 'COMPLETE')),
    response_status INTEGER,
    response_reason VARCHAR(64),
    response_body BYTEA,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((state = 'IN_FLIGHT' AND response_status IS NULL AND response_body IS NULL)
        OR (state = 'COMPLETE' AND response_status IS NOT NULL AND response_body IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS payment_idempotency_expiry ON payment_idempotency (expires_at);
