package com.example.sharding.functional;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional Verification Test — Concurrency
 *
 * <p>Verifies that the shard routing context (ThreadLocal) is fully
 * thread-safe: no context leaks between threads, concurrent operations
 * resolve to the correct shard, and no race conditions occur under
 * parallel load.
 */
@DisplayName("FVT: Concurrency — Thread Safety")
public class ConcurrencyFVT extends FunctionalTestBase {

    @BeforeEach
    void setup() {
        resetMocks();
        stubSave();
    }

    // ── FVT-CC-01: Concurrent writes all succeed without error ────────────────

    @Test
    @DisplayName("FVT-CC-01: 200 concurrent createOrder calls all succeed without exception")
    void concurrentWrites_allSucceed() throws InterruptedException {
        int threadCount = 200;
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failure = new AtomicInteger(0);
        CountDownLatch latch  = new CountDownLatch(threadCount);
        ExecutorService pool  = Executors.newFixedThreadPool(20);

        for (int i = 0; i < threadCount; i++) {
            final long userId = i;
            pool.submit(() -> {
                try {
                    orderService.createOrder(userId, BigDecimal.TEN);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).isEqualTo(threadCount);
        assertThat(failure.get()).isZero();
    }

    // ── FVT-CC-02: Each thread resolves its own correct shard ─────────────────

    @Test
    @DisplayName("FVT-CC-02: Each concurrent thread resolves exactly its own shard index")
    void eachThread_resolvesOwnShard() throws InterruptedException {
        int threadCount = 90; // 30 per shard (0,1,2 → userId 0..89)
        ConcurrentHashMap<Long, Integer> results = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(30);

        for (int i = 0; i < threadCount; i++) {
            final long userId = i;
            pool.submit(() -> {
                try {
                    ShardContextHolder.setShard(DataSourceConfig.resolveShardIndex(userId));
                    Thread.sleep(new Random().nextInt(3));
                    results.put(userId, ShardContextHolder.getShard());
                } catch (InterruptedException ignored) {
                } finally {
                    ShardContextHolder.clear();
                    latch.countDown();
                }
            });
        }

        latch.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        for (Map.Entry<Long, Integer> entry : results.entrySet()) {
            int expected = DataSourceConfig.resolveShardIndex(entry.getKey());
            assertThat(entry.getValue())
                .as("userId=%d should map to shard %d", entry.getKey(), expected)
                .isEqualTo(expected);
        }
    }

    // ── FVT-CC-03: Main thread context not affected by worker threads ─────────

    @Test
    @DisplayName("FVT-CC-03: Worker thread activity does not pollute the main thread context")
    void workerThreads_doNotPollutMainThread() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(10);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ShardContextHolder.setShard(2);
                ShardContextHolder.setRole(Role.REPLICA);
                ShardContextHolder.clear();
                latch.countDown();
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Main thread context must be untouched
        assertThat(ShardContextHolder.getShard()).isNull();
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-CC-04: Context is clean on each new thread ────────────────────────

    @Test
    @DisplayName("FVT-CC-04: A new thread always starts with null shard and default PRIMARY role")
    void newThread_startsWithCleanContext() throws InterruptedException {
        // Set context on main thread
        ShardContextHolder.setShard(1);
        ShardContextHolder.setRole(Role.REPLICA);

        AtomicReference<Integer> threadShard = new AtomicReference<>();
        AtomicReference<Role>    threadRole  = new AtomicReference<>();

        Thread t = new Thread(() -> {
            threadShard.set(ShardContextHolder.getShard());
            threadRole.set(ShardContextHolder.getRole());
        });
        t.start();
        t.join();

        assertThat(threadShard.get()).isNull();
        assertThat(threadRole.get()).isEqualTo(Role.PRIMARY);

        ShardContextHolder.clear();
    }

    // ── FVT-CC-05: Shard distribution remains balanced under concurrent load ──

    @Test
    @DisplayName("FVT-CC-05: Concurrent shard resolution distributes 300 requests evenly across 3 shards")
    void concurrentShardResolution_isBalanced() throws InterruptedException {
        int total = 300;
        int[] counts = new int[DataSourceConfig.NUM_SHARDS];
        CountDownLatch latch = new CountDownLatch(total);
        ExecutorService pool = Executors.newFixedThreadPool(30);
        ConcurrentHashMap<Integer, AtomicInteger> shardCounts = new ConcurrentHashMap<>();
        for (int s = 0; s < DataSourceConfig.NUM_SHARDS; s++) {
            shardCounts.put(s, new AtomicInteger(0));
        }

        for (int i = 0; i < total; i++) {
            final long userId = i;
            pool.submit(() -> {
                int shard = DataSourceConfig.resolveShardIndex(userId);
                shardCounts.get(shard).incrementAndGet();
                latch.countDown();
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        int expected = total / DataSourceConfig.NUM_SHARDS; // 100
        for (int s = 0; s < DataSourceConfig.NUM_SHARDS; s++) {
            assertThat(shardCounts.get(s).get())
                .as("Shard %d", s)
                .isEqualTo(expected);
        }
    }

    // ── FVT-CC-06: Service remains functional after concurrent load ───────────

    @Test
    @DisplayName("FVT-CC-06: Service is still functional after 100 concurrent operations")
    void serviceRemainsCorrect_afterConcurrentLoad() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(20);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    orderService.createOrder(
                        ThreadLocalRandom.current().nextLong(0, 1000),
                        BigDecimal.TEN);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        // After all concurrent work, the main thread can still make a clean call
        orderService.createOrder(1L, BigDecimal.ONE);
        assertThat(ShardContextHolder.getShard()).isNull();
    }
}
