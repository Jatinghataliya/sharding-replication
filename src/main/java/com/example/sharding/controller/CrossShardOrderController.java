package com.example.sharding.controller;

import com.example.sharding.dto.FanOutResult;
import com.example.sharding.service.CrossShardQueryService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "Admin Cross-Shard Orders", description = "Cross-shard admin queries that fan out in parallel to all database shards.")
public class CrossShardOrderController {

    private final CrossShardQueryService crossShardQueryService;

    public CrossShardOrderController(CrossShardQueryService crossShardQueryService) {
        this.crossShardQueryService = crossShardQueryService;
    }

    // ── GET /api/admin/orders?status=PENDING ──────────────────────────────────

    @Operation(
        summary = "Get orders across all shards by status",
        description = "Fans out query to all shards in parallel and aggregates orders with matching status."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Query succeeded across all shards",
            content = @Content(schema = @Schema(implementation = FanOutResult.class))),
        @ApiResponse(responseCode = "206", description = "Partial content (one or more shards degraded/failed)",
            content = @Content(schema = @Schema(implementation = FanOutResult.class)))
    })
    @GetMapping
    public ResponseEntity<FanOutResult> getOrdersByStatus(
            @Parameter(description = "Order status to filter by across all shards", example = "PENDING", required = true)
            @RequestParam String status) {
        FanOutResult result = crossShardQueryService.findOrdersByStatus(status);
        HttpStatus httpStatus = result.isDegraded() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(httpStatus).body(result);
    }

    // ── GET /api/admin/orders/user/{userId}/all ───────────────────────────────

    @Operation(
        summary = "Get orders across all shards for a user",
        description = "Fans out query across all shards for reconciliation or debugging purposes."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Query succeeded across all shards",
            content = @Content(schema = @Schema(implementation = FanOutResult.class))),
        @ApiResponse(responseCode = "206", description = "Partial content (one or more shards degraded/failed)",
            content = @Content(schema = @Schema(implementation = FanOutResult.class)))
    })
    @GetMapping("/user/{userId}/all")
    public ResponseEntity<FanOutResult> getAllOrdersForUser(
            @Parameter(description = "User ID to query across all shards", example = "101")
            @PathVariable long userId) {
        FanOutResult result = crossShardQueryService.findOrdersByUser(userId);
        HttpStatus httpStatus = result.isDegraded() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(httpStatus).body(result);
    }

    // ── GET /api/admin/orders/count ───────────────────────────────────────────

    @Operation(
        summary = "Count orders per shard",
        description = "Queries all shards in parallel to get count of orders on each shard."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Per-shard order counts returned")
    })
    @GetMapping("/count")
    public ResponseEntity<Map<Integer, Long>> countOrdersPerShard() {
        return ResponseEntity.ok(crossShardQueryService.countOrdersPerShard());
    }
}
