package com.example.sharding.suite;

import com.example.sharding.aspect.TransactionRoutingAspectTest;
import com.example.sharding.behaviour.BehaviourTestSuite;
import com.example.sharding.config.DataSourceConfigTest;
import com.example.sharding.suite.FunctionalVerificationSuite;
import com.example.sharding.context.DataSourceKeyTest;
import com.example.sharding.context.ShardContextHolderTest;
import com.example.sharding.controller.OrderControllerTest;
import com.example.sharding.load.LoadTest;
import com.example.sharding.service.OrderServiceTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║           MASTER TEST SUITE — Sharding + Replication            ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * <p>Aggregates every test class in the project into a single runnable suite.
 * Executing this class runs all tests across four logical groups:
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  GROUP 1 — Unit Tests (49 tests)                                │
 * │  ├── ShardContextHolderTest     (8)  ThreadLocal isolation      │
 * │  ├── DataSourceKeyTest          (8)  equals / hashCode          │
 * │  ├── DataSourceConfigTest      (15)  shard resolution logic     │
 * │  ├── TransactionRoutingAspect   (6)  role detection             │
 * │  └── OrderServiceTest          (12)  business logic (mocked)    │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  GROUP 2 — API / Integration Tests (12 tests)                   │
 * │  └── OrderControllerTest       (12)  MockMvc HTTP layer         │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  GROUP 3 — Load Tests (6 tests)                                 │
 * │  └── LoadTest                   (6)  concurrency & throughput   │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  GROUP 4 — Behaviour / BDD Tests (Cucumber scenarios)           │
 * │  └── BehaviourTestSuite             order-management            │
 * │                                     shard-routing               │
 * │                                     replication-routing         │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Run options</h3>
 * <pre>
 *   # Full master suite (all 67 tests):
 *   mvn test -Dtest=ShardingTestSuite
 *
 *   # Unit tests only:
 *   mvn test -Dtest=UnitTestSuite
 *
 *   # API tests only:
 *   mvn test -Dtest=ApiTestSuite
 *
 *   # Load tests only:
 *   mvn test -Dtest=LoadTestSuite
 *
 *   # JMH performance benchmarks (run separately — takes longer):
 *   mvn test -Dtest=PerformanceTest -DfailIfNoTests=false
 *
 *   # Behaviour (BDD/Cucumber) tests only:
 *   mvn test -Dtest=BehaviourTestSuite
 *
 *   # Everything via Maven default lifecycle (excludes JMH):
 *   mvn test
 * </pre>
 */
@Suite
@SuiteDisplayName("Sharding & Replication — Full Test Suite")
@SelectClasses({
    // ── Group 1: Unit Tests ───────────────────────────────────────────
    ShardContextHolderTest.class,
    DataSourceKeyTest.class,
    DataSourceConfigTest.class,
    TransactionRoutingAspectTest.class,
    OrderServiceTest.class,

    // ── Group 2: API / Integration Tests ─────────────────────────────
    OrderControllerTest.class,

    // ── Group 3: Load Tests ───────────────────────────────────────────
    LoadTest.class,

    // ── Group 4: Behaviour / BDD Tests ───────────────────────────────
    BehaviourTestSuite.class,

    // ── Group 5: Functional Verification Tests ────────────────────────
    FunctionalVerificationSuite.class
})
public class ShardingTestSuite {
    // Suite marker class — no body needed
}
