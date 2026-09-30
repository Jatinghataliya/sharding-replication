package com.example.sharding.service;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import com.example.sharding.entity.Order;
import com.example.sharding.exception.OrderNotFoundException;
import com.example.sharding.idempotency.IdempotencyService;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.resilience.ShardCircuitBreakerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for order management.
 *
 * <h3>Circuit Breaker + Retry</h3>
 * Every repository call is wrapped through a per-shard
 * {@link ShardCircuitBreakerService}. If a shard's DB is unreachable:
 * <ol>
 *   <li>The {@link io.github.resilience4j.retry.Retry} retries up to 3 times
 *       with exponential back-off (500ms → 1s → 2s).</li>
 *   <li>If retries are exhausted, the failure is counted against the
 *       {@link io.github.resilience4j.circuitbreaker.CircuitBreaker} sliding window.</li>
 *   <li>Once the failure rate exceeds 50%, the circuit trips OPEN and subsequent
 *       calls fail immediately with
 *       {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
 *       → mapped to HTTP 503 by {@link com.example.sharding.controller.GlobalExceptionHandler}.</li>
 * </ol>
 *
 * <h3>Idempotency</h3>
 * {@link #createOrder(long, BigDecimal, String)} accepts an optional
 * {@code idempotencyKey}. A matching non-expired record returns the original
 * order (HTTP 200 replay) instead of creating a duplicate.
 *
 * <h3>Routing</h3>
 * <pre>
 *   createOrder / updateOrderStatus → @Transactional        → AOP → PRIMARY
 *   getOrdersByUser / getOrderById  → @Transactional(readOnly) → AOP → REPLICA
 * </pre>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository            orderRepository;
    private final IdempotencyService         idempotencyService;
    private final ShardCircuitBreakerService circuitBreakerService;

    public OrderService(OrderRepository            orderRepository,
                        IdempotencyService         idempotencyService,
                        ShardCircuitBreakerService circuitBreakerService) {
        this.orderRepository       = orderRepository;
        this.idempotencyService    = idempotencyService;
        this.circuitBreakerService = circuitBreakerService;
    }

    // ── Create (with idempotency + circuit breaker) ───────────────────────────

    /**
     * Creates a new order, honouring idempotency when a key is provided.
     * The repository save is wrapped in the shard's circuit breaker.
     *
     * @param userId         shard key and owner of the order
     * @param amount         order amount
     * @param idempotencyKey client-supplied key, or {@code null} / blank
     * @return wrapper containing the order and a replay flag
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException
     *         if the shard's circuit is OPEN (upstream maps to HTTP 503)
     */
    @Transactional
    public OrderResult createOrder(long userId, BigDecimal amount, String idempotencyKey) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);

        // ── Idempotency check (read-through circuit breaker for REPLICA) ──────
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.validateKey(idempotencyKey);

            Optional<Order> existing = idempotencyService.findExistingOrder(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent replay: key={}, userId={}, shard={}", idempotencyKey, userId, shard);
                return OrderResult.replay(existing.get());
            }
        }

        // ── First-time execution — wrap save in circuit breaker ───────────────
        log.info("createOrder → userId={}, shard={}, role=PRIMARY", userId, shard);
        Order order = new Order(userId, amount);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            order.setIdempotencyKey(idempotencyKey);
        }

        Order saved = circuitBreakerService.execute(shard, Role.PRIMARY,
                () -> orderRepository.save(order));

        // Persist idempotency record on the same shard
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.saveRecord(idempotencyKey, saved);
        }

        return OrderResult.created(saved);
    }

    // ── Read (circuit breaker on REPLICA) ─────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Order> getOrdersByUser(long userId) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("getOrdersByUser → userId={}, shard={}, role=REPLICA", userId, shard);

        return circuitBreakerService.execute(shard, Role.REPLICA,
                () -> orderRepository.findByUserId(userId));
    }

    @Transactional(readOnly = true)
    public Order getOrderById(long userId, long orderId) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("getOrderById → orderId={}, userId={}, shard={}, role=REPLICA", orderId, userId, shard);

        return circuitBreakerService.execute(shard, Role.REPLICA,
                () -> orderRepository.findById(orderId)
                        .orElseThrow(() -> new OrderNotFoundException(orderId, shard)));
    }

    // ── Update (circuit breaker on PRIMARY) ───────────────────────────────────

    @Transactional
    public Order updateOrderStatus(long userId, long orderId, String status) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("updateOrderStatus → orderId={}, status={}, shard={}, role=PRIMARY", orderId, status, shard);

        return circuitBreakerService.execute(shard, Role.PRIMARY, () -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId, shard));
            order.setStatus(status);
            return orderRepository.save(order);
        });
    }

    // ── Result wrapper ────────────────────────────────────────────────────────

    /**
     * Thin wrapper returned by {@link #createOrder} so the controller can
     * distinguish a newly created order (HTTP 201) from a replayed one (HTTP 200).
     */
    public static class OrderResult {
        private final Order   order;
        private final boolean isReplay;

        private OrderResult(Order order, boolean isReplay) {
            this.order    = order;
            this.isReplay = isReplay;
        }

        public static OrderResult created(Order order) { return new OrderResult(order, false); }
        public static OrderResult replay(Order order)  { return new OrderResult(order, true);  }

        public Order   getOrder()  { return order;    }
        public boolean isReplay()  { return isReplay; }
    }
}
