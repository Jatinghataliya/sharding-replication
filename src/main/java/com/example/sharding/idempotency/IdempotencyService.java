package com.example.sharding.idempotency;

import com.example.sharding.entity.IdempotencyRecord;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.IdempotencyRepository;
import com.example.sharding.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Manages idempotency for the {@code POST /api/orders} endpoint.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>First request with a key → execute normally, persist an
 *       {@link IdempotencyRecord}, return HTTP 201.</li>
 *   <li>Retry with same key (within TTL) → skip execution, return the
 *       original {@link Order} with HTTP 200.</li>
 *   <li>Expired key → treated as a new request.</li>
 * </ul>
 *
 * <h3>Storage</h3>
 * The record is stored on the same shard as the order (same datasource
 * routing context), so no cross-shard coordination is needed.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository idempotencyRepository;
    private final OrderRepository       orderRepository;

    @Value("${idempotency.ttl-hours:24}")
    private int ttlHours;

    public IdempotencyService(IdempotencyRepository idempotencyRepository,
                              OrderRepository orderRepository) {
        this.idempotencyRepository = idempotencyRepository;
        this.orderRepository       = orderRepository;
    }

    /**
     * Looks up an existing record by key.
     *
     * @return {@code Optional} containing the previously created {@link Order},
     *         or empty if this is the first request (or the key has expired).
     */
    @Transactional(readOnly = true)
    public Optional<Order> findExistingOrder(String idempotencyKey) {
        return idempotencyRepository.findById(idempotencyKey)
                .filter(r -> !r.isExpired())
                .flatMap(r -> {
                    log.info("Idempotency hit: key={}, orderId={}", idempotencyKey, r.getOrderId());
                    return orderRepository.findById(r.getOrderId());
                });
    }

    /**
     * Persists a new idempotency record after a successful order creation.
     *
     * @param idempotencyKey the client-supplied key
     * @param order          the newly created order
     */
    @Transactional
    public void saveRecord(String idempotencyKey, Order order) {
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);
        IdempotencyRecord record = new IdempotencyRecord(
                idempotencyKey, order.getUserId(), order.getOrderId(), expiresAt);
        idempotencyRepository.save(record);
        log.info("Idempotency record saved: key={}, orderId={}, expiresAt={}",
                idempotencyKey, order.getOrderId(), expiresAt);
    }

    /**
     * Validates that an idempotency key is syntactically acceptable.
     * Keys must be non-blank and at most 64 characters.
     *
     * @throws IllegalArgumentException if the key is invalid
     */
    public void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required for POST /api/orders");
        }
        if (key.length() > 64) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 64 characters or fewer");
        }
    }
}
