package com.example.sharding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Request body for creating a new order")
public class CreateOrderRequest {

    @Schema(description = "ID of the user placing the order (used as the shard key)", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Schema(description = "Order amount in decimal", example = "250.00", requiredMode = Schema.RequiredMode.REQUIRED)
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
