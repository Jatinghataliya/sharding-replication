package com.example.sharding.suite;

import com.example.sharding.functional.AdminApiContractFVT;
import com.example.sharding.functional.CrossShardFVT;
import com.example.sharding.service.CrossShardQueryServiceTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Cross-Shard Query Test Suite
 *
 * <p>Aggregates unit, functional verification, and API contract tests for
 * cross-shard fan-out query operations.</p>
 */
@Suite
@SuiteDisplayName("Cross-Shard Query Test Suite")
@SelectClasses({
    CrossShardQueryServiceTest.class,
    CrossShardFVT.class,
    AdminApiContractFVT.class
})
public class CrossShardQuerySuite {
    // Suite marker class — no body needed
}
