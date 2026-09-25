-- Run this script on ALL shard primary databases:
-- shard0_db, shard1_db, shard2_db

CREATE TABLE IF NOT EXISTS orders (
    order_id   BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    amount     DECIMAL(10, 2) NOT NULL,
    status     VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
