-- Run this script on ALL shard primary databases:
-- shard0_db, shard1_db, shard2_db

CREATE TABLE IF NOT EXISTS orders (
    order_id        BIGSERIAL      PRIMARY KEY,
    user_id         BIGINT         NOT NULL,
    amount          DECIMAL(10, 2) NOT NULL,
    status          VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    idempotency_key VARCHAR(64)    NULL
);

CREATE INDEX  IF NOT EXISTS idx_orders_user_id        ON orders(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uidx_idempotency_key ON orders(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ── Idempotency store ──────────────────────────────────────────────────────────
-- Stores the result of the first successful execution so that retried requests
-- receive the exact same response without re-executing the business logic.

CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key VARCHAR(64)   PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    order_id        BIGINT        NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP     NOT NULL
);
