package com.example.sharding.performance;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.DataSourceKey;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmark suite measuring the latency of core shard routing operations.
 *
 * <p>These benchmarks target the hot path: shard resolution, context set/get,
 * and DataSourceKey hash/equality — operations that run on every single request.</p>
 *
 * <p>Run standalone with: {@code mvn test -Dtest=PerformanceTest}</p>
 * <p>Or run the JMH runner directly from your IDE by executing {@link #main}.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class PerformanceTest {

    private static final int NUM_SHARDS = DataSourceConfig.NUM_SHARDS;

    // ── Shard resolution ─────────────────────────────────────────────────────

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public int benchmark_shardResolution_throughput() {
        return DataSourceConfig.resolveShardIndex(101L);
    }

    @Benchmark
    public int benchmark_shardResolution_latency() {
        return DataSourceConfig.resolveShardIndex(12345L);
    }

    @Benchmark
    public int benchmark_shardResolution_negativeUserId() {
        return DataSourceConfig.resolveShardIndex(-999L);
    }

    // ── ThreadLocal context ───────────────────────────────────────────────────

    @Benchmark
    public void benchmark_contextHolder_setAndGet() {
        ShardContextHolder.setShard(1);
        ShardContextHolder.setRole(Role.REPLICA);
        ShardContextHolder.getShard();
        ShardContextHolder.getRole();
        ShardContextHolder.clear();
    }

    @Benchmark
    public void benchmark_contextHolder_setShard() {
        ShardContextHolder.setShard(2);
    }

    @Benchmark
    public Integer benchmark_contextHolder_getShard() {
        return ShardContextHolder.getShard();
    }

    @Benchmark
    public Role benchmark_contextHolder_getRole_default() {
        return ShardContextHolder.getRole();
    }

    // ── DataSourceKey hash/equality ───────────────────────────────────────────

    @Benchmark
    public DataSourceKey benchmark_dataSourceKey_create() {
        return new DataSourceKey(1, Role.PRIMARY);
    }

    @Benchmark
    public int benchmark_dataSourceKey_hashCode() {
        return new DataSourceKey(2, Role.REPLICA).hashCode();
    }

    @Benchmark
    public boolean benchmark_dataSourceKey_equals() {
        DataSourceKey a = new DataSourceKey(1, Role.PRIMARY);
        DataSourceKey b = new DataSourceKey(1, Role.PRIMARY);
        return a.equals(b);
    }

    // ── Full hot-path simulation ──────────────────────────────────────────────

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public DataSourceKey benchmark_fullHotPath() {
        // Simulates what happens on every request:
        // 1. resolve shard from userId
        int shard = DataSourceConfig.resolveShardIndex(101L);
        // 2. store in ThreadLocal
        ShardContextHolder.setShard(shard);
        ShardContextHolder.setRole(Role.REPLICA);
        // 3. build routing key
        DataSourceKey key = new DataSourceKey(
                ShardContextHolder.getShard(),
                ShardContextHolder.getRole());
        // 4. cleanup
        ShardContextHolder.clear();
        return key;
    }

    // ── JUnit 5 entry point ───────────────────────────────────────────────────

    /**
     * Run as a JUnit test so Maven Surefire picks it up during {@code mvn test}.
     * Prints a summary table of results to stdout.
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("JMH Performance Benchmarks — shard routing hot path")
    void runBenchmarks() throws RunnerException {
        Options opts = new OptionsBuilder()
                .include(PerformanceTest.class.getSimpleName())
                .warmupIterations(2)
                .measurementIterations(3)
                .forks(1)
                .shouldFailOnError(true)
                .build();

        new Runner(opts).run();
    }

    /**
     * Standalone entry point — run directly from an IDE for full JMH output.
     */
    public static void main(String[] args) throws RunnerException {
        Options opts = new OptionsBuilder()
                .include(PerformanceTest.class.getSimpleName())
                .build();
        new Runner(opts).run();
    }
}
