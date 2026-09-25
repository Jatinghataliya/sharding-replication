package com.example.sharding.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "Represents a customer order stored on a sharded PostgreSQL database")
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Order {

    @Schema(description = "Auto-generated order ID (unique within the shard)", example = "1")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Schema(description = "ID of the user who placed the order — also the shard key", example = "101")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "Order amount", example = "250.00")
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Schema(description = "Current order status", example = "PENDING", allowableValues = {"PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED"})
    @Column(name = "status", nullable = false, length = 50)
    private String status = "PENDING";

    @Schema(description = "Timestamp when the order was created", example = "2024-06-01T10:30:00")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Order(Long userId, BigDecimal amount) {
        this.userId    = userId;
        this.amount    = amount;
        this.status    = "PENDING";
        this.createdAt = LocalDateTime.now();
    }
}
