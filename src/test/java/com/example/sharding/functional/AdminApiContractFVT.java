package com.example.sharding.functional;

import com.example.sharding.controller.CrossShardOrderController;
import com.example.sharding.controller.GlobalExceptionHandler;
import com.example.sharding.dto.FanOutResult;
import com.example.sharding.dto.ShardQueryResult;
import com.example.sharding.entity.Order;
import com.example.sharding.service.CrossShardQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional Verification Test — Admin Cross-Shard API Contract
 */
@WebMvcTest(controllers = {CrossShardOrderController.class, GlobalExceptionHandler.class})
@DisplayName("FVT: Admin Cross-Shard API Contract")
public class AdminApiContractFVT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CrossShardQueryService crossShardQueryService;

    // ── FVT-ADM-01: GET /api/admin/orders?status=PENDING → 200 with merged body

    @Test
    @DisplayName("FVT-ADM-01: GET /api/admin/orders?status=PENDING returns 200 and merged FanOutResult")
    void getOrdersByStatus_returns200() throws Exception {
        List<ShardQueryResult> shardResults = List.of(
                new ShardQueryResult(0, List.of(buildOrder(1L, 100L, "PENDING")), 15L),
                new ShardQueryResult(1, List.of(buildOrder(2L, 101L, "PENDING")), 12L),
                new ShardQueryResult(2, List.of(buildOrder(3L, 102L, "PENDING")), 18L)
        );
        FanOutResult fanOutResult = new FanOutResult(3, shardResults, 20L, 3, false);

        when(crossShardQueryService.findOrdersByStatus("PENDING")).thenReturn(fanOutResult);

        mockMvc.perform(get("/api/admin/orders").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalOrders").value(3))
                .andExpect(jsonPath("$.shardsQueried").value(3))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.resultsPerShard", hasSize(3)))
                .andExpect(jsonPath("$.resultsPerShard[0].shardIndex").value(0))
                .andExpect(jsonPath("$.resultsPerShard[0].orders[0].orderId").value(1));
    }

    // ── FVT-ADM-02: Partial failure → 206 Partial Content with degraded: true ──

    @Test
    @DisplayName("FVT-ADM-02: GET /api/admin/orders with partial shard failure returns 206 Partial Content")
    void getOrdersByStatus_degraded_returns206() throws Exception {
        List<ShardQueryResult> shardResults = List.of(
                new ShardQueryResult(0, List.of(buildOrder(1L, 100L, "PENDING")), 15L),
                ShardQueryResult.failure(1, 10L, "Shard 1 timeout"),
                new ShardQueryResult(2, List.of(buildOrder(3L, 102L, "PENDING")), 18L)
        );
        FanOutResult fanOutResult = new FanOutResult(2, shardResults, 25L, 3, true);

        when(crossShardQueryService.findOrdersByStatus("PENDING")).thenReturn(fanOutResult);

        mockMvc.perform(get("/api/admin/orders").param("status", "PENDING"))
                .andExpect(status().isPartialContent())
                .andExpect(jsonPath("$.totalOrders").value(2))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.resultsPerShard[1].success").value(false))
                .andExpect(jsonPath("$.resultsPerShard[1].errorMessage").value("Shard 1 timeout"));
    }

    // ── FVT-ADM-03: GET /api/admin/orders/count → 200 with per-shard map ───────

    @Test
    @DisplayName("FVT-ADM-03: GET /api/admin/orders/count returns 200 with map of shard counts")
    void countOrdersPerShard_returns200WithMap() throws Exception {
        Map<Integer, Long> counts = Map.of(0, 10L, 1, 25L, 2, 40L);
        when(crossShardQueryService.countOrdersPerShard()).thenReturn(counts);

        mockMvc.perform(get("/api/admin/orders/count"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.0").value(10))
                .andExpect(jsonPath("$.1").value(25))
                .andExpect(jsonPath("$.2").value(40));
    }

    // ── FVT-ADM-04: GET /api/admin/orders/user/{userId}/all → 200 ─────────────

    @Test
    @DisplayName("FVT-ADM-04: GET /api/admin/orders/user/{userId}/all returns 200")
    void getAllOrdersForUser_returns200() throws Exception {
        List<ShardQueryResult> shardResults = List.of(
                new ShardQueryResult(0, List.of(), 5L),
                new ShardQueryResult(1, List.of(), 5L),
                new ShardQueryResult(2, List.of(buildOrder(1L, 101L, "PENDING")), 10L)
        );
        FanOutResult fanOutResult = new FanOutResult(1, shardResults, 12L, 3, false);

        when(crossShardQueryService.findOrdersByUser(101L)).thenReturn(fanOutResult);

        mockMvc.perform(get("/api/admin/orders/user/101/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(1))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.resultsPerShard[2].orders[0].userId").value(101));
    }

    // ── FVT-ADM-05: Missing status parameter → 400 Bad Request ────────────────

    @Test
    @DisplayName("FVT-ADM-05: GET /api/admin/orders without status param returns 400")
    void getOrdersByStatus_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isBadRequest());
    }

    private Order buildOrder(long id, long userId, String status) {
        Order o = new Order(userId, BigDecimal.valueOf(100.00));
        o.setOrderId(id);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
