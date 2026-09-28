package com.example.sharding.service;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.dto.FanOutResult;
import com.example.sharding.dto.ShardQueryResult;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CrossShardQueryService Unit Tests")
public class CrossShardQueryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private CrossShardQueryService crossShardQueryService;

    @BeforeEach
    void setUp() {
        crossShardQueryService = new CrossShardQueryService(orderRepository, Executors.newFixedThreadPool(3));
    }

    @AfterEach
    void tearDown() {
        crossShardQueryService.shutdown();
        ShardContextHolder.clear();
    }

    // ── 1. All 3 shards queried in parallel ───────────────────────────────────

    @Test
    @DisplayName("findOrdersByStatus queries all 3 shards in parallel and captures contexts")
    void findOrdersByStatus_queriesAllShards() {
        Set<Integer> visitedShards = ConcurrentHashMap.newKeySet();

        when(orderRepository.findByStatus("PENDING")).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard != null) {
                visitedShards.add(shard);
            }
            return List.of(buildOrder(10L + (shard != null ? shard : 0), 100L, "PENDING", LocalDateTime.now()));
        });

        FanOutResult result = crossShardQueryService.findOrdersByStatus("PENDING");

        assertThat(result.getShardsQueried()).isEqualTo(3);
        assertThat(result.getResultsPerShard()).hasSize(3);
        assertThat(visitedShards).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(result.isDegraded()).isFalse();
        assertThat(result.getTotalOrders()).isEqualTo(3);
    }

    // ── 2. Results from all shards merged correctly ───────────────────────────

    @Test
    @DisplayName("findOrdersByStatus merges and sorts results from all shards by createdAt DESC")
    void findOrdersByStatus_mergesAndSortsResults() {
        LocalDateTime t1 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2024, 1, 1, 11, 0);
        LocalDateTime t3 = LocalDateTime.of(2024, 1, 1, 12, 0);

        when(orderRepository.findByStatus("CONFIRMED")).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard == null) return List.of();
            switch (shard) {
                case 0: return List.of(buildOrder(1L, 100L, "CONFIRMED", t1));
                case 1: return List.of(buildOrder(2L, 101L, "CONFIRMED", t3));
                case 2: return List.of(buildOrder(3L, 102L, "CONFIRMED", t2));
                default: return List.of();
            }
        });

        FanOutResult result = crossShardQueryService.findOrdersByStatus("CONFIRMED");

        assertThat(result.isDegraded()).isFalse();
        assertThat(result.getTotalOrders()).isEqualTo(3);
        assertThat(result.getResultsPerShard()).hasSize(3);

        // Verify order in individual shard results
        ShardQueryResult shard0 = result.getResultsPerShard().get(0);
        ShardQueryResult shard1 = result.getResultsPerShard().get(1);
        ShardQueryResult shard2 = result.getResultsPerShard().get(2);

        assertThat(shard0.getOrders().get(0).getOrderId()).isEqualTo(1L);
        assertThat(shard1.getOrders().get(0).getOrderId()).isEqualTo(2L);
        assertThat(shard2.getOrders().get(0).getOrderId()).isEqualTo(3L);
    }

    // ── 3. Partial failure returns degraded result ────────────────────────────

    @Test
    @DisplayName("findOrdersByStatus handles partial shard failure returning degraded=true and remaining shard data")
    void findOrdersByStatus_partialFailure_returnsDegraded() {
        when(orderRepository.findByStatus("PENDING")).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard != null && shard == 1) {
                throw new RuntimeException("Shard 1 connection timeout");
            }
            return List.of(buildOrder(1L, 100L, "PENDING", LocalDateTime.now()));
        });

        FanOutResult result = crossShardQueryService.findOrdersByStatus("PENDING");

        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getShardsQueried()).isEqualTo(3);
        assertThat(result.getTotalOrders()).isEqualTo(2); // Shards 0 and 2 succeeded
        assertThat(result.getResultsPerShard()).hasSize(3);

        ShardQueryResult shard1 = result.getResultsPerShard().get(1);
        assertThat(shard1.isSuccess()).isFalse();
        assertThat(shard1.getErrorMessage()).contains("Shard 1 connection timeout");
        assertThat(shard1.getOrders()).isEmpty();

        ShardQueryResult shard0 = result.getResultsPerShard().get(0);
        assertThat(shard0.isSuccess()).isTrue();
        assertThat(shard0.getOrders()).hasSize(1);
    }

    // ── 4. Empty results from all shards ───────────────────────────────────────

    @Test
    @DisplayName("findOrdersByStatus returns totalOrders=0 when all shards have no data")
    void findOrdersByStatus_emptyResults_returnsEmpty() {
        when(orderRepository.findByStatus("UNKNOWN")).thenReturn(List.of());

        FanOutResult result = crossShardQueryService.findOrdersByStatus("UNKNOWN");

        assertThat(result.isDegraded()).isFalse();
        assertThat(result.getTotalOrders()).isEqualTo(0);
        assertThat(result.getShardsQueried()).isEqualTo(3);
        assertThat(result.getResultsPerShard()).hasSize(3);
        for (ShardQueryResult sqr : result.getResultsPerShard()) {
            assertThat(sqr.isSuccess()).isTrue();
            assertThat(sqr.getOrders()).isEmpty();
        }
    }

    // ── 5. countOrdersPerShard returns correct per-shard counts ───────────────

    @Test
    @DisplayName("countOrdersPerShard returns map of counts for each shard")
    void countOrdersPerShard_returnsCorrectCounts() {
        when(orderRepository.count()).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard == null) return 0L;
            switch (shard) {
                case 0: return 10L;
                case 1: return 25L;
                case 2: return 40L;
                default: return 0L;
            }
        });

        Map<Integer, Long> counts = crossShardQueryService.countOrdersPerShard();

        assertThat(counts).hasSize(3);
        assertThat(counts.get(0)).isEqualTo(10L);
        assertThat(counts.get(1)).isEqualTo(25L);
        assertThat(counts.get(2)).isEqualTo(40L);
    }

    @Test
    @DisplayName("countOrdersPerShard handles shard exception by returning -1 for that shard")
    void countOrdersPerShard_handlesException() {
        when(orderRepository.count()).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard != null && shard == 2) {
                throw new RuntimeException("Shard 2 DB down");
            }
            return 15L;
        });

        Map<Integer, Long> counts = crossShardQueryService.countOrdersPerShard();

        assertThat(counts).hasSize(3);
        assertThat(counts.get(0)).isEqualTo(15L);
        assertThat(counts.get(1)).isEqualTo(15L);
        assertThat(counts.get(2)).isEqualTo(-1L);
    }

    // ── 6. findOrdersByUser fan-out test ──────────────────────────────────────

    @Test
    @DisplayName("findOrdersByUser queries all shards and merges results")
    void findOrdersByUser_queriesAllShards() {
        when(orderRepository.findByUserId(101L)).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard != null && shard == 2) {
                return List.of(buildOrder(1L, 101L, "PENDING", LocalDateTime.now()));
            }
            return List.of();
        });

        FanOutResult result = crossShardQueryService.findOrdersByUser(101L);

        assertThat(result.isDegraded()).isFalse();
        assertThat(result.getTotalOrders()).isEqualTo(1);
        assertThat(result.getResultsPerShard()).hasSize(3);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Order buildOrder(long id, long userId, String status, LocalDateTime createdAt) {
        Order o = new Order(userId, BigDecimal.valueOf(100.00));
        o.setOrderId(id);
        o.setStatus(status);
        o.setCreatedAt(createdAt);
        return o;
    }
}
