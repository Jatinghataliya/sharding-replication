package com.example.sharding.suite;

import com.example.sharding.idempotency.IdempotencyTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Idempotency Test Suite
 *
 * <p>Verifies the full idempotency contract for {@code POST /api/orders}:
 * <ul>
 *   <li>First request → new order created (HTTP 201 / isReplay=false)</li>
 *   <li>Retry with same key → cached order returned (HTTP 200 / isReplay=true)</li>
 *   <li>Expired key → new order created</li>
 *   <li>No key / blank key → always creates, no idempotency record</li>
 *   <li>Invalid key (too long, null) → IllegalArgumentException → 400</li>
 *   <li>TTL, field binding, different keys, replay flag</li>
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dtest=IdempotencyTestSuite}
 */
@Suite
@SuiteDisplayName("Idempotency Tests — POST /api/orders Idempotency Contract (13 tests)")
@SelectClasses({
    IdempotencyTest.class
})
public class IdempotencyTestSuite {
    // Suite marker class — no body needed
}
