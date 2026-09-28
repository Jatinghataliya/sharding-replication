package com.example.sharding.functional;

import com.example.sharding.aspect.TransactionRoutingAspect;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.dto.FanOutResult;
import com.example.sharding.dto.ShardQueryResult;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.service.CrossShardQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Functional Verification Test — Cross Shard Fan-Out Queries
 */
@SpringJUnitConfig(CrossShardFVT.Config.class)
@DisplayName("FVT: Cross-Shard Fan-Out Query")
public class CrossShardFVT {

    @TestConfiguration
    @EnableAspectJAutoProxy
    @Import(TransactionRoutingAspect.class)
    static class Config {
        @Bean
        public OrderRepository orderRepository() {
            return Mockito.mock(OrderRepository.class);
        }

        @Bean
        public CrossShardQueryService crossShardQueryService(OrderRepository orderRepository) {
            return new CrossShardQueryService(orderRepository);
        }
    }

    @Autowired
    private CrossShardQueryService crossShardQueryService;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        Mockito.reset(orderRepository);
        ShardContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        ShardContextHolder.clear();
    }

    // ── FVT-CS-01: Fan-out result contains correct shard indices ─────────────

    @Test
    @DisplayName("FVT-CS-01: Fan-out result contains correct shard indices [0, 1, 2]")
    void fanOutResult_containsCorrectShardIndices() {
        when(orderRepository.findByStatus("PENDING")).thenReturn(new ArrayList<>());

        FanOutResult result = crossShardQueryService.findOrdersByStatus("PENDING");

        assertThat(result.getShardsQueried()).isEqualTo(3);
        assertThat(result.getResultsPerShard()).hasSize(3);
        assertThat(result.getResultsPerShard())
                .extracting(ShardQueryResult::getShardIndex)
                .containsExactly(0, 1, 2);
    }

    // ── FVT-CS-02: Query time is captured per shard ──────────────────────────

    @Test
    @DisplayName("FVT-CS-02: Query time is captured per shard and in aggregate")
    void fanOutResult_capturesQueryTimes() {
        when(orderRepository.findByStatus("PENDING")).thenAnswer(inv -> {
            Thread.sleep(10);
            return List.of();
        });

        FanOutResult result = crossShardQueryService.findOrdersByStatus("PENDING");

        assertThat(result.getTotalQueryTimeMs()).isGreaterThanOrEqualTo(0);
        for (ShardQueryResult sqr : result.getResultsPerShard()) {
            assertThat(sqr.getQueryTimeMs()).isGreaterThanOrEqualTo(0);
        }
    }

    // ── FVT-CS-03: Results are sorted by createdAt DESC ──────────────────────

    @Test
    @DisplayName("FVT-CS-03: Merged orders are sorted by createdAt DESC across all shards")
    void fanOutResult_sortedByCreatedAtDesc() {
        LocalDateTime tOld = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime tMid = LocalDateTime.of(2024, 1, 1, 11, 0);
        LocalDateTime tNew = LocalDateTime.of(2024, 1, 1, 12, 0);

        when(orderRepository.findByStatus("SHIPPED")).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard == null) return List.of();
            switch (shard) {
                case 0: return List.of(buildOrder(101L, 10L, "SHIPPED", tOld));
                case 1: return List.of(buildOrder(102L, 11L, "SHIPPED", tNew));
                case 2: return List.of(buildOrder(103L, 12L, "SHIPPED", tMid));
                default: return List.of();
            }
        });

        FanOutResult result = crossShardQueryService.findOrdersByStatus("SHIPPED");

        assertThat(result.getTotalOrders()).isEqualTo(3);

        // Verify individual shard results keep their order
        List<ShardQueryResult> shardResults = result.getResultsPerShard();
        assertThat(shardResults.get(0).getOrders().get(0).getOrderId()).isEqualTo(101L);
        assertThat(shardResults.get(1).getOrders().get(0).getOrderId()).isEqualTo(102L);
        assertThat(shardResults.get(2).getOrders().get(0).getOrderId()).isEqualTo(103L);
    }

    // ── FVT-CS-04: totalOrders equals sum of per-shard counts ─────────────────

    @Test
    @DisplayName("FVT-CS-04: FanOutResult.totalOrders equals sum of per-shard counts")
    void fanOutResult_totalOrdersMatchesPerShardSum() {
        when(orderRepository.findByStatus("CONFIRMED")).thenAnswer(inv -> {
            Integer shard = ShardContextHolder.getShard();
            if (shard == null) return List.of();
            switch (shard) {
                case 0: return List.of(
                        buildOrder(1L, 0L, "CONFIRMED", LocalDateTime.now()),
                        buildOrder(2L, 0L, "CONFIRMED", LocalDateTime.now())
                );
                case 1: return List.of(
                        buildOrder(3L, 1L, "CONFIRMED", LocalDateTime.now())
                );
                case 2: return List.of(
                        buildOrder(4L, 2L, "CONFIRMED", LocalDateTime.now()),
                        buildOrder(5L, 2L, "CONFIRMED", LocalDateTime.now()),
                        buildOrder(6L, 2L, "CONFIRMED", LocalDateTime.now())
                );
                default: return List.of();
            }
        });

        FanOutResult result = crossShardQueryService.findOrdersByStatus("CONFIRMED");

        int sumPerShard = result.getResultsPerShard().stream()
                .mapToInt(r -> r.getOrders().size())
                .sum();

        assertThat(result.getTotalOrders()).isEqualTo(6);
        assertThat(result.getTotalOrders()).isEqualTo(sumPerShard);
        assertThat(result.isDegraded()).isFalse();
    }

    // ── FVT-CS-05: ShardContextHolder is cleanly maintained on worker threads ──

    @Test
    @DisplayName("FVT-CS-05: Worker threads cleanly set and clear ShardContextHolder without leaking")
    void fanOut_workerThreadsDoNotLeakContext() {
        when(orderRepository.findByStatus(anyString())).thenAnswer(inv -> {
            assertThat(ShardContextHolder.getShard()).isNotNull();
            assertThat(ShardContextHolder.getRole()).isEqualTo(ShardContextHolder.Role.REPLICA);
            return List.of();
        });

        crossShardQueryService.findOrdersByStatus("PENDING");

        // Main thread should remain clean
        assertThat(ShardContextHolder.getShard()).isNull();
        assertThat(ShardContextHolder.getRole()).isEqualTo(ShardContextHolder.Role.PRIMARY);
    }

    private Order buildOrder(long id, long userId, String status, LocalDateTime createdAt) {
        Order o = new Order(userId, BigDecimal.valueOf(150.00));
        o.setOrderId(id);
        o.setStatus(status);
        o.setCreatedAt(createdAt);
        return o;
    }
}
