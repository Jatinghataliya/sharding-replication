package com.example.sharding.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Persisted record of an idempotency key and the order it produced.
 *
 * <p>Stored on the same shard as the order, so the lookup stays local.
 * TTL is enforced at the application layer via {@code expiresAt}.</p>
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public IdempotencyRecord(String idempotencyKey, Long userId, Long orderId,
                             LocalDateTime expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId         = userId;
        this.orderId        = orderId;
        this.createdAt      = LocalDateTime.now();
        this.expiresAt      = expiresAt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
