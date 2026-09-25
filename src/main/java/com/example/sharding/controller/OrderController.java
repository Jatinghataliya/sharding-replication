package com.example.sharding.controller;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.dto.CreateOrderRequest;
import com.example.sharding.entity.Order;
import com.example.sharding.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "CRUD operations for orders. Each request is automatically routed to the correct shard (PRIMARY for writes, REPLICA for reads).")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(
        summary = "Create a new order",
        description = "Persists a new order on the PRIMARY of the shard resolved from `userId`. Shard = userId % 3."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order created",
            content = @Content(schema = @Schema(implementation = Order.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content)
    })
    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest request) {
        Order saved = orderService.createOrder(request.getUserId(), request.getAmount());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(
        summary = "Get all orders for a user",
        description = "Fetches all orders belonging to a user from the REPLICA of the resolved shard."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of orders returned",
            content = @Content(schema = @Schema(implementation = Order.class)))
    })
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getOrdersByUser(
            @Parameter(description = "ID of the user whose orders to retrieve", example = "101")
            @PathVariable long userId) {
        return ResponseEntity.ok(orderService.getOrdersByUser(userId));
    }

    @Operation(
        summary = "Get a single order by ID",
        description = "Fetches one order from the REPLICA of the resolved shard. `userId` is required to determine the correct shard."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order found",
            content = @Content(schema = @Schema(implementation = Order.class))),
        @ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrderById(
            @Parameter(description = "ID of the order", example = "1")
            @PathVariable long orderId,
            @Parameter(description = "ID of the user who owns the order (used for shard resolution)", example = "101")
            @RequestParam long userId) {
        return ResponseEntity.ok(orderService.getOrderById(userId, orderId));
    }

    @Operation(
        summary = "Update order status",
        description = "Updates the status of an existing order on the PRIMARY of the resolved shard."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order status updated",
            content = @Content(schema = @Schema(implementation = Order.class))),
        @ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<Order> updateStatus(
            @Parameter(description = "ID of the order to update", example = "1")
            @PathVariable long orderId,
            @Parameter(description = "ID of the user who owns the order (used for shard resolution)", example = "101")
            @RequestParam long userId,
            @Parameter(description = "New status value", example = "SHIPPED")
            @RequestParam String status) {
        return ResponseEntity.ok(orderService.updateOrderStatus(userId, orderId, status));
    }

    @Operation(
        summary = "Get shard info for a userId",
        description = "Utility endpoint — shows which shard index a given `userId` maps to, without touching the database."
    )
    @ApiResponse(responseCode = "200", description = "Shard mapping returned")
    @GetMapping("/shard-info")
    public ResponseEntity<Map<String, Object>> shardInfo(
            @Parameter(description = "User ID to resolve shard for", example = "101")
            @RequestParam long userId) {
        return ResponseEntity.ok(Map.of(
                "userId",      userId,
                "shardIndex",  DataSourceConfig.resolveShardIndex(userId),
                "totalShards", DataSourceConfig.NUM_SHARDS
        ));
    }
}
