package com.example.sharding.suite;

import com.example.sharding.aspect.TransactionRoutingAspectTest;
import com.example.sharding.config.DataSourceConfigTest;
import com.example.sharding.context.DataSourceKeyTest;
import com.example.sharding.context.ShardContextHolderTest;
import com.example.sharding.service.OrderServiceTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Unit Test Suite
 *
 * <p>Covers pure logic with no Spring context or database:
 * <ul>
 *   <li>ThreadLocal shard/role context</li>
 *   <li>DataSourceKey equality and hashing</li>
 *   <li>Hash-based shard resolution</li>
 *   <li>AOP role-detection logic</li>
 *   <li>OrderService business logic (mocked repository)</li>
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dtest=UnitTestSuite}
 */
@Suite
@SuiteDisplayName("Unit Tests — Context, Routing Logic, Service")
@SelectClasses({
    ShardContextHolderTest.class,
    DataSourceKeyTest.class,
    DataSourceConfigTest.class,
    TransactionRoutingAspectTest.class,
    OrderServiceTest.class
})
public class UnitTestSuite {
    // Suite marker class — no body needed
}
