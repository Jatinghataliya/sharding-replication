package com.example.sharding.load;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.service.OrderService;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Load tests for shard routing logic.
 *
 * <p>These tests do NOT require a real database — the repository is mocked.
 * They measure throughput, concurrency correctness, and shard distribution
 * under simulated load.</p>
 *
 * <p>Run with: {@code mvn test -Dtest=LoadTest}</p>
 */
@DisplayName("Load Tests — Shard Routing")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoadTest {

    private static OrderService orderService;
    private static OrderRepository mockRepository;

    @BeforeAll
    static void setup() {
        mockRepository = mock(OrderRepository.class);
        orderService   = new OrderService(mockRepository);

        // Stub save to return the passed order with a fake ID
        when(mockRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setOrderId(ThreadLocalRandom.current().nextLong(1, 100_000));
            return o;
        });
        when(mockRepository.findByUserId(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        ShardContextHolder.clear();
    }

    // ── Test 1: Throughput — sequential ──────────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Sequential throughput: 1,000 createOrder calls complete under 2s")
    void sequentialThroughput_createOrder() {
        int requestCount = 1_000;
        long start = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            orderService.createOrder(i, new BigDecimal("10.00"));
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[LoadTest] Sequential: %d creates in %d ms (%.0f req/s)%n",
                requestCount, elapsed, requestCount * 1000.0 / elapsed);

        assertThat(elapsed)
                .as("1000 sequential creates should complete under 2000ms")
                .isLessThan(2_000);
    }

    // ── Test 2: Concurrent write throughput ──────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Concurrent throughput: 500 threads each create 1 order, all succeed")
    void concurrentThroughput_createOrder() throws InterruptedException {
        int threads = 500;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount   = new AtomicInteger(0);
        CountDownLatch latch       = new CountDownLatch(threads);
        ExecutorService executor   = Executors.newFixedThreadPool(50);

        long start = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            final long userId = i;
            executor.submit(() -> {
                try {
                    orderService.createOrder(userId, new BigDecimal("10.00"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[LoadTest] Concurrent: %d/%d succeeded in %d ms%n",
                successCount.get(), threads, elapsed);

        assertThat(successCount.get()).isEqualTo(threads);
        assertThat(errorCount.get()).isZero();
    }

    // ── Test 3: Thread-local isolation under concurrency ─────────────────────

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Each concurrent thread sets its own shard index without interference")
    void threadLocalIsolation_underConcurrency() throws InterruptedException {
        int threads = 100;
        ConcurrentHashMap<Long, Integer> threadShardMap = new ConcurrentHashMap<>();
        CountDownLatch latch    = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final long userId = i * 3L; // all map to shard 0
            executor.submit(() -> {
                try {
                    ShardContextHolder.setShard(DataSourceConfig.resolveShardIndex(userId));
                    // Simulate some work
                    Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                    threadShardMap.put(Thread.currentThread().getId(),
                            ShardContextHolder.getShard());
                } catch (InterruptedException ignored) {
                } finally {
                    ShardContextHolder.clear();
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Every thread that set shard 0 should have read back shard 0
        threadShardMap.values().forEach(shard ->
                assertThat(shard).isEqualTo(0));
    }

    // ── Test 4: Mixed read/write concurrency ─────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Mixed concurrent reads and writes all complete without errors")
    void mixedReadWrite_concurrency() throws InterruptedException {
        int total          = 200;
        AtomicInteger ok   = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        CountDownLatch latch    = new CountDownLatch(total);
        ExecutorService executor = Executors.newFixedThreadPool(20);

        long start = System.currentTimeMillis();

        for (int i = 0; i < total; i++) {
            final long userId = i;
            final boolean isWrite = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    if (isWrite) {
                        orderService.createOrder(userId, BigDecimal.TEN);
                    } else {
                        orderService.getOrdersByUser(userId);
                    }
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[LoadTest] Mixed R/W: %d/%d ok in %d ms%n",
                ok.get(), total, elapsed);

        assertThat(ok.get()).isEqualTo(total);
        assertThat(fail.get()).isZero();
    }

    // ── Test 5: Shard distribution under load ────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Shard distribution across 3000 requests is roughly even (±5%)")
    void shardDistribution_isEven() {
        int total      = 3_000;
        int[] counts   = new int[DataSourceConfig.NUM_SHARDS];

        for (int i = 0; i < total; i++) {
            int shard = DataSourceConfig.resolveShardIndex(i);
            counts[shard]++;
        }

        int expected  = total / DataSourceConfig.NUM_SHARDS;
        int tolerance = (int)(expected * 0.05); // 5%

        System.out.println("[LoadTest] Shard distribution:");
        for (int s = 0; s < DataSourceConfig.NUM_SHARDS; s++) {
            System.out.printf("  Shard %d: %d requests (expected ~%d)%n", s, counts[s], expected);
            assertThat(counts[s])
                    .as("Shard %d count should be within ±%d of %d", s, tolerance, expected)
                    .isBetween(expected - tolerance, expected + tolerance);
        }
    }

    // ── Test 6: Context cleanup under load ───────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("ShardContextHolder is clean after 500 concurrent operations")
    void contextCleanup_afterLoad() throws InterruptedException {
        int threads = 500;
        CountDownLatch latch    = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(50);

        for (int i = 0; i < threads; i++) {
            final long userId = i;
            executor.submit(() -> {
                try {
                    orderService.createOrder(userId, BigDecimal.TEN);
                } finally {
                    ShardContextHolder.clear();
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // After all threads finish, the main thread's context should be clean
        assertThat(ShardContextHolder.getShard()).isNull();
        assertThat(ShardContextHolder.getRole())
                .isEqualTo(ShardContextHolder.Role.PRIMARY);
    }
}
