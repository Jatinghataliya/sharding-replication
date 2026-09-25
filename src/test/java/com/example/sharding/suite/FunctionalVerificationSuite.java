package com.example.sharding.suite;

import com.example.sharding.functional.ApiContractFVT;
import com.example.sharding.functional.ConcurrencyFVT;
import com.example.sharding.functional.DataIntegrityFVT;
import com.example.sharding.functional.OrderLifecycleFVT;
import com.example.sharding.functional.ReplicationRoutingFVT;
import com.example.sharding.functional.ShardRoutingFVT;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Functional Verification Test Suite
 *
 * <p>Aggregates all FVT classes that verify the end-to-end observable behaviour
 * of the sharding + replication system without a real database.
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  FVT Class                  Tests  What it verifies             │
 * ├──────────────────────────────────────────────────────────────────┤
 * │  OrderLifecycleFVT            10   create→read→update lifecycle  │
 * │  ShardRoutingFVT              10   routing key, context, cleanup  │
 * │  ReplicationRoutingFVT        10   PRIMARY/REPLICA role split     │
 * │  ApiContractFVT               13   HTTP status, body, errors      │
 * │  DataIntegrityFVT             10   amounts, timestamps, fields    │
 * │  ConcurrencyFVT                6   thread safety, no leaks       │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Run with: {@code mvn test -Dtest=FunctionalVerificationSuite}
 */
@Suite
@SuiteDisplayName("Functional Verification Tests — End-to-End Observable Behaviour")
@SelectClasses({
    OrderLifecycleFVT.class,
    ShardRoutingFVT.class,
    ReplicationRoutingFVT.class,
    ApiContractFVT.class,
    DataIntegrityFVT.class,
    ConcurrencyFVT.class
})
public class FunctionalVerificationSuite {
    // Suite marker class — no body needed
}
