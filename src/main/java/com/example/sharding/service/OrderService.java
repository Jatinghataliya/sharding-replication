package com.example.sharding.service;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Business logic for order management.
 *
 * <p>Each method sets the shard index in {@link ShardContextHolder} before
 * delegating to the repository. The AOP aspect in
 * {@link com.example.sharding.aspect.TransactionRoutingAspect} independently
 * sets the read/write role based on the {@code @Transactional} annotation,
 * so this service only needs to worry about the shard key.</p>
 *
 * <p>Routing summary:
 * <pre>
 *   createOrder  → @Transactional          → AOP sets PRIMARY → writes go to primary
 *   getOrders    → @Transactional(readOnly) → AOP sets REPLICA → reads go to replica
 * </pre>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Creates a new order on the shard that owns the given {@code userId}.
     * Routes to the <strong>primary</strong> of that shard (write operation).
     */
    @Transactional
    public Order createOrder(long userId, BigDecimal amount) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("createOrder → userId={}, shard={}, role=PRIMARY", userId, shard);

        return orderRepository.save(new Order(userId, amount));
    }

    /**
     * Fetches all orders for the given {@code userId} from the correct shard replica.
     * Routes to the <strong>replica</strong> of that shard (read operation).
     */
    @Transactional(readOnly = true)
    public List<Order> getOrdersByUser(long userId) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("getOrdersByUser → userId={}, shard={}, role=REPLICA", userId, shard);

        return orderRepository.findByUserId(userId);
    }

    /**
     * Fetches a single order by its ID from the correct shard.
     * The caller must pass the {@code userId} so we can resolve the shard.
     */
    @Transactional(readOnly = true)
    public Order getOrderById(long userId, long orderId) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("getOrderById → orderId={}, userId={}, shard={}, role=REPLICA",
                orderId, userId, shard);

        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException(
                        "Order not found: id=" + orderId + " on shard=" + shard));
    }

    /**
     * Updates the status of an order on the primary of the correct shard.
     */
    @Transactional
    public Order updateOrderStatus(long userId, long orderId, String status) {
        int shard = DataSourceConfig.resolveShardIndex(userId);
        ShardContextHolder.setShard(shard);
        log.info("updateOrderStatus → orderId={}, status={}, shard={}, role=PRIMARY",
                orderId, status, shard);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: id=" + orderId));
        order.setStatus(status);
        return orderRepository.save(order);
    }
}
