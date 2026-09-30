package com.example.sharding.suite;

import com.example.sharding.functional.CircuitBreakerFVT;
import com.example.sharding.resilience.CircuitBreakerActuatorTest;
import com.example.sharding.resilience.CircuitBreakerTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Suite runner for all Circuit Breaker + Retry tests.
 *
 * <pre>
 * CircuitBreakerTest          13  Unit — Resilience4j CB/Retry mechanics
 * CircuitBreakerFVT            7  FVT  — Through OrderService (real CB, mocked repo)
 * CircuitBreakerActuatorTest   8  HTTP — MockMvc 503/404 contract tests
 * ─────────────────────────────
 * Total                       28
 * </pre>
 *
 * <p>Run with: {@code mvn test -Dtest=CircuitBreakerSuite}
 */
@Suite
@SuiteDisplayName("Circuit Breaker + Retry — Full Suite")
@SelectClasses({
    CircuitBreakerTest.class,
    CircuitBreakerFVT.class,
    CircuitBreakerActuatorTest.class
})
public class CircuitBreakerSuite {
    // Suite marker — no body needed
}
