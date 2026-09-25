package com.example.sharding.suite;

import com.example.sharding.load.LoadTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Load Test Suite
 *
 * <p>Covers concurrency, throughput, and shard distribution under simulated load.
 * No real database is required — the repository is mocked.
 *
 * <ul>
 *   <li>Sequential throughput: 1,000 creates must complete under 2 s</li>
 *   <li>Concurrent writes: 500 threads all succeed without errors</li>
 *   <li>ThreadLocal isolation: each thread sees only its own shard context</li>
 *   <li>Mixed R/W concurrency: 200 interleaved reads and writes all succeed</li>
 *   <li>Shard distribution: 3,000 requests distributed ±5% evenly across 3 shards</li>
 *   <li>Context cleanup: ShardContextHolder is clean after 500 concurrent ops</li>
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dtest=LoadTestSuite}
 */
@Suite
@SuiteDisplayName("Load Tests — Concurrency, Throughput & Shard Distribution")
@SelectClasses({
    LoadTest.class
})
public class LoadTestSuite {
    // Suite marker class — no body needed
}
