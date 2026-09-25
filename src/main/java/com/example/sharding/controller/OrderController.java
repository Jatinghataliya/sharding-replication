package com.example.sharding.controller;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.dto.CreateOrderRequest;
import com.example.sharding.entity.Order;
import com.example.sharding.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing CRUD endpoints for orders.
 *
 * <p>All routing decisions (shard selection + read/write splitting)
 * are handled transparently by the service and AOP layers.</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /api/orders
     * Creates a new order. Routes to the PRIMARY of the resolved shard.
     */
    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest request) {
        Order saved = orderService.createOrder(request.getUserId(), request.getAmount());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * GET /api/orders/user/{userId}
     * Returns all orders for a user. Routes to the REPLICA of the resolved shard.
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getOrdersByUser(@PathVariable long userId) {
        return ResponseEntity.ok(orderService.getOrdersByUser(userId));
    }

    /**
     * GET /api/orders/{orderId}?userId={userId}
     * Returns a single order. userId is required to resolve the correct shard.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrderById(
            @PathVariable long orderId,
            @RequestParam long userId) {
        return ResponseEntity.ok(orderService.getOrderById(userId, orderId));
    }

    /**
     * PATCH /api/orders/{orderId}/status?userId={userId}
     * Updates order status. Routes to the PRIMARY of the resolved shard.
     */
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<Order> updateStatus(
            @PathVariable long orderId,
            @RequestParam long userId,
            @RequestParam String status) {
        return ResponseEntity.ok(orderService.updateOrderStatus(userId, orderId, status));
    }

    /**
     * GET /api/orders/shard-info?userId={userId}
     * Utility endpoint — shows which shard a userId maps to.
     */
    @GetMapping("/shard-info")
    public ResponseEntity<Map<String, Object>> shardInfo(@RequestParam long userId) {
        return ResponseEntity.ok(Map.of(
                "userId",     userId,
                "shardIndex", DataSourceConfig.resolveShardIndex(userId),
                "totalShards", DataSourceConfig.NUM_SHARDS
        ));
    }
}
