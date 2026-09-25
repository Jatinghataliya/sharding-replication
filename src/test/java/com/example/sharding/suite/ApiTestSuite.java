package com.example.sharding.suite;

import com.example.sharding.controller.OrderControllerTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * API / Integration Test Suite
 *
 * <p>Covers the full HTTP layer using Spring MockMvc with a mocked service:
 * <ul>
 *   <li>POST /api/orders — create order, missing body</li>
 *   <li>GET  /api/orders/user/{userId} — list orders, empty list</li>
 *   <li>GET  /api/orders/{orderId} — single order, not found, missing param</li>
 *   <li>PATCH /api/orders/{orderId}/status — update, not found</li>
 *   <li>GET  /api/orders/shard-info — shard mapping, missing param</li>
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dtest=ApiTestSuite}
 */
@Suite
@SuiteDisplayName("API Tests — OrderController (MockMvc)")
@SelectClasses({
    OrderControllerTest.class
})
public class ApiTestSuite {
    // Suite marker class — no body needed
}
