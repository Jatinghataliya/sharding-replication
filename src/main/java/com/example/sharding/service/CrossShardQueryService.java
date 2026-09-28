package com.example.sharding.service;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import com.example.sharding.dto.FanOutResult;
import com.example.sharding.dto.ShardQueryResult;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.OrderRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Service that coordinates parallel cross-shard fan-out queries.
 *
 * <p>Each shard query runs asynchronously on a dedicated thread pool via
 * {@link CompletableFuture}, setting its own {@link ShardContextHolder}
 * shard index and {@link Role#REPLICA} role. Partial shard failures are handled
 * gracefully with degraded results.</p>
 */
@Service
public class CrossShardQueryService {

    private static final Logger log = LoggerFactory.getLogger(CrossShardQueryService.class);

    private final OrderRepository orderRepository;
    private final ExecutorService executorService;
    private final int numShards;

    public CrossShardQueryService(OrderRepository orderRepository) {
        this(orderRepository, DataSourceConfig.NUM_SHARDS);
    }

    public CrossShardQueryService(
            OrderRepository orderRepository,
            @Value("${crossshard.executor.threads:" + DataSourceConfig.NUM_SHARDS + "}") int threadPoolSize) {
        this.orderRepository = orderRepository;
        this.numShards = DataSourceConfig.NUM_SHARDS;
        this.executorService = Executors.newFixedThreadPool(
                threadPoolSize > 0 ? threadPoolSize : DataSourceConfig.NUM_SHARDS,
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "cross-shard-query-" + (++count));
                        t.setDaemon(true);
                        return t;
                    }
                }
        );
    }

    /**
     * Constructor allowing a custom ExecutorService (useful for unit testing).
     */
    public CrossShardQueryService(OrderRepository orderRepository, ExecutorService executorService) {
        this.orderRepository = orderRepository;
        this.numShards = DataSourceConfig.NUM_SHARDS;
        this.executorService = executorService;
    }

    @PreDestroy
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    // ── Per-shard queries (Transactional on replica) ──────────────────────────

    @Transactional(readOnly = true)
    public List<Order> queryOrdersByStatusOnShard(int shardIndex, String status) {
        ShardContextHolder.setShard(shardIndex);
        ShardContextHolder.setRole(Role.REPLICA);
        try {
            log.debug("Querying status='{}' on shard={}, role=REPLICA", status, shardIndex);
            return orderRepository.findByStatus(status);
        } finally {
            ShardContextHolder.clear();
        }
    }

    @Transactional(readOnly = true)
    public List<Order> queryOrdersByUserOnShard(int shardIndex, long userId) {
        ShardContextHolder.setShard(shardIndex);
        ShardContextHolder.setRole(Role.REPLICA);
        try {
            log.debug("Querying userId={} on shard={}, role=REPLICA", userId, shardIndex);
            return orderRepository.findByUserId(userId);
        } finally {
            ShardContextHolder.clear();
        }
    }

    @Transactional(readOnly = true)
    public long countOrdersOnShard(int shardIndex) {
        ShardContextHolder.setShard(shardIndex);
        ShardContextHolder.setRole(Role.REPLICA);
        try {
            log.debug("Counting orders on shard={}, role=REPLICA", shardIndex);
            return orderRepository.count();
        } finally {
            ShardContextHolder.clear();
        }
    }

    // ── Fan-out Coordinators (Non-transactional) ──────────────────────────────

    /**
     * Queries all shards in parallel for orders with a given status, merges results
     * sorted by {@code createdAt DESC}, and returns a {@link FanOutResult}.
     */
    public FanOutResult findOrdersByStatus(String status) {
        return executeFanOutQuery(shardIndex -> queryOrdersByStatusOnShard(shardIndex, status));
    }

    /**
     * Demonstrates fan-out across all shards for a given user ID (e.g. cross-shard reconciliation),
     * merges results sorted by {@code createdAt DESC}.
     */
    public FanOutResult findOrdersByUser(long userId) {
        return executeFanOutQuery(shardIndex -> queryOrdersByUserOnShard(shardIndex, userId));
    }

    /**
     * Counts orders per shard in parallel across all shards.
     *
     * @return Map of shard index to order count (or -1 if shard failed)
     */
    public Map<Integer, Long> countOrdersPerShard() {
        long start = System.currentTimeMillis();
        List<CompletableFuture<Map.Entry<Integer, Long>>> futures = new ArrayList<>();

        for (int i = 0; i < numShards; i++) {
            final int shardIndex = i;
            CompletableFuture<Map.Entry<Integer, Long>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    long count = countOrdersOnShard(shardIndex);
                    return Map.entry(shardIndex, count);
                } catch (Exception e) {
                    log.error("Failed to count orders on shard {}: {}", shardIndex, e.getMessage());
                    return Map.entry(shardIndex, -1L);
                }
            }, executorService);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<Integer, Long> result = new LinkedHashMap<>();
        for (CompletableFuture<Map.Entry<Integer, Long>> f : futures) {
            try {
                Map.Entry<Integer, Long> entry = f.get();
                result.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.error("Error retrieving shard count future result", e);
            }
        }
        return result;
    }

    // ── Internal Fan-Out Engine ───────────────────────────────────────────────

    private FanOutResult executeFanOutQuery(ShardQueryFunction queryFunction) {
        long overallStart = System.currentTimeMillis();
        List<CompletableFuture<ShardQueryResult>> futures = new ArrayList<>();

        for (int i = 0; i < numShards; i++) {
            final int shardIndex = i;
            CompletableFuture<ShardQueryResult> future = CompletableFuture.supplyAsync(() -> {
                long shardStart = System.currentTimeMillis();
                try {
                    List<Order> orders = queryFunction.query(shardIndex);
                    long duration = System.currentTimeMillis() - shardStart;
                    return new ShardQueryResult(shardIndex, orders != null ? orders : new ArrayList<>(), duration);
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - shardStart;
                    log.warn("Shard {} query failed after {}ms: {}", shardIndex, duration, e.getMessage());
                    return ShardQueryResult.failure(shardIndex, duration, e.getMessage());
                }
            }, executorService);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<ShardQueryResult> shardResults = new ArrayList<>();
        List<Order> mergedOrders = new ArrayList<>();
        boolean degraded = false;

        for (CompletableFuture<ShardQueryResult> f : futures) {
            try {
                ShardQueryResult res = f.get();
                shardResults.add(res);
                if (res.isSuccess()) {
                    mergedOrders.addAll(res.getOrders());
                } else {
                    degraded = true;
                }
            } catch (Exception e) {
                log.error("Error retrieving future result", e);
                degraded = true;
            }
        }

        // Sort merged orders by createdAt DESC (nulls last)
        mergedOrders.sort((o1, o2) -> {
            if (o1.getCreatedAt() == null && o2.getCreatedAt() == null) return 0;
            if (o1.getCreatedAt() == null) return 1;
            if (o2.getCreatedAt() == null) return -1;
            return o2.getCreatedAt().compareTo(o1.getCreatedAt());
        });

        long totalTime = System.currentTimeMillis() - overallStart;

        // Ensure shardResults is sorted by shardIndex for determinism
        shardResults.sort(Comparator.comparingInt(ShardQueryResult::getShardIndex));

        return new FanOutResult(
                mergedOrders.size(),
                shardResults,
                totalTime,
                numShards,
                degraded
        );
    }

    @FunctionalInterface
    private interface ShardQueryFunction {
        List<Order> query(int shardIndex) throws Exception;
    }
}
