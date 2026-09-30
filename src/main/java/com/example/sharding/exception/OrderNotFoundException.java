package com.example.sharding.exception;

/**
 * Thrown when an order cannot be found on its expected shard.
 *
 * <p>This exception is deliberately NOT counted as a circuit-breaker failure
 * (listed under {@code ignoreExceptions} in application.yml).
 * A missing order is a business-logic condition, not an infrastructure fault.
 */
public class OrderNotFoundException extends RuntimeException {

    private final long orderId;
    private final int  shardIndex;

    public OrderNotFoundException(long orderId, int shardIndex) {
        super("Order not found: id=" + orderId + " on shard=" + shardIndex);
        this.orderId    = orderId;
        this.shardIndex = shardIndex;
    }

    public long getOrderId()    { return orderId;    }
    public int  getShardIndex() { return shardIndex; }
}
