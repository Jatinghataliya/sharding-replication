package com.example.sharding.dto;

import java.math.BigDecimal;

/** Request body for creating an order. */
public class CreateOrderRequest {

    private Long userId;
    private BigDecimal amount;

    public CreateOrderRequest() {}

    public CreateOrderRequest(Long userId, BigDecimal amount) {
        this.userId = userId;
        this.amount = amount;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
