package com.example.sharding.behaviour;

import org.junit.platform.suite.api.*;

/**
 * Cucumber BDD test runner — executes all .feature files under
 * {@code src/test/resources/features/}.
 *
 * <p>Run with:
 * <pre>
 *   mvn test -Dtest=BehaviourTestSuite
 * </pre>
 *
 * <p>Feature files:
 * <ul>
 *   <li>{@code order-management.feature}  — create / read / update order lifecycle</li>
 *   <li>{@code shard-routing.feature}     — shard resolution and context propagation</li>
 *   <li>{@code replication-routing.feature} — read/write splitting (PRIMARY vs REPLICA)</li>
 * </ul>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(
    key   = "cucumber.glue",
    value = "com.example.sharding.behaviour"
)
@ConfigurationParameter(
    key   = "cucumber.plugin",
    value = "pretty, summary, html:target/cucumber-reports/behaviour-report.html"
)
@ConfigurationParameter(
    key   = "cucumber.publish.quiet",
    value = "true"
)
public class BehaviourTestSuite {
    // Suite marker class — no body needed
}
