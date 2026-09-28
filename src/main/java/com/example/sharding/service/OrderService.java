package com.example.sharding.service;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.entity.Order;
import com.example.sharding.idempotency.IdempotencyService;
import com.example.sharding.repository.OrderRepository;
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
 * <h3>Idempotency</h3>
 * {@link #createOrder(long, BigDecimal, String)} accepts an optional
 * {@code idempotencyKey}. When a key is supplied:
 * <ol>
 *   <li>If a matching, non-expired record exists → the original order is returned
 *       and {@code isIdempotentReplay} flag is set {@code true} on the result
 *       wrapper.</li>
 *   <li>Otherwise → a new order is created, the record is persisted, and
 *       {@code isIdempotentReplay} is {@code false}.</li>
 * </ol>
 *
 * <h3>Routing</h3>
 * <pre>
 *   createOrder  → @Transactional          → AOP sets PRIMARY → writes go to primary
 *   getOrders    → @Transactional(readOnly) → AOP sets REPLICA → reads go to replica
 * </pre>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository     orderRepository;
    private final IdempotencyService  idempotencyService;

    public OrderService(OrderRepository    orderRepository,
                        IdempotencyService idempotencyService) {
        this.orderRepository    = orderRepository;
        this.idempotencyService = idempotencyService;
    }

    // ── Create (with idempotency) ─────────────────────────────────────────────

    /**
     * Creates a new order, honouring idempotency when a key is provided.
     *
     * @param userId          shard key and owner of the order
     * @param amount          order amount
     * @param idempotencyKey  client-supplied key, or {@code null} / blank
     * @return wrapper containing the order and a replay flag
     */
    @Transactional
    public OrderResult createOrder(long userId, BigDecimal amount, String idempotencyKey) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);

        // ── Idempotency check ─────────────────────────────────────────────────
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.validateKey(idempotencyKey);

            Optional<Order> existing = idempotencyService.findExistingOrder(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent replay: key={}, userId={}, shard={}", idempotencyKey, userId, shard);
                return OrderResult.replay(existing.get());
            }
        }

        // ── First-time execution ──────────────────────────────────────────────
        log.info("createOrder → userId={}, shard={}, role=PRIMARY", userId, shard);
        Order order = new Order(userId, amount);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            order.setIdempotencyKey(idempotencyKey);
        }
        Order saved = orderRepository.save(order);

        // Persist idempotency record on the same shard
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.saveRecord(idempotencyKey, saved);
        }

        return OrderResult.created(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Order> getOrdersByUser(long userId) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("getOrdersByUser → userId={}, shard={}, role=REPLICA", userId, shard);
        return orderRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Order getOrderById(long userId, long orderId) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("getOrderById → orderId={}, userId={}, shard={}, role=REPLICA", orderId, userId, shard);
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException(
                        "Order not found: id=" + orderId + " on shard=" + shard));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public Order updateOrderStatus(long userId, long orderId, String status) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("updateOrderStatus → orderId={}, status={}, shard={}, role=PRIMARY", orderId, status, shard);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: id=" + orderId));
        order.setStatus(status);
        return orderRepository.save(order);
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

        public Order   getOrder()    { return order;    }
        public boolean isReplay()    { return isReplay; }
    }
}
