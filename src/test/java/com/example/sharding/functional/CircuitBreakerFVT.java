package com.example.sharding.functional;

import com.example.sharding.aspect.TransactionRoutingAspect;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import com.example.sharding.entity.Order;
import com.example.sharding.exception.OrderNotFoundException;
import com.example.sharding.idempotency.IdempotencyService;
import com.example.sharding.repository.IdempotencyRepository;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.resilience.ShardCircuitBreakerService;
import com.example.sharding.service.OrderService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Functional Verification Tests — Circuit Breaker behaviour through the full
 * {@link OrderService} stack.
 *
 * <p>Uses a real {@link ShardCircuitBreakerService} with an aggressive
 * test-only config (window=4, threshold=50%, wait=50ms) so circuit state
 * transitions can be observed within a single test without real timeouts.
 * A fresh {@link ShardCircuitBreakerService} bean is created per test class
 * (prototype scope via factory), but since all tests share the same Spring
 * context, each test resets the circuit breakers it touches in {@link #setup()}.
 *
 * <p>The {@link OrderRepository} is mocked — no real database required.
 */
@SpringJUnitConfig(CircuitBreakerFVT.Config.class)
@DisplayName("FVT: Circuit Breaker — OrderService Integration")
public class CircuitBreakerFVT {

    // ── Self-contained Spring context ─────────────────────────────────────────

    @TestConfiguration
    @EnableAspectJAutoProxy
    @Import({OrderService.class, TransactionRoutingAspect.class, IdempotencyService.class})
    static class Config {

        @Bean
        public OrderRepository orderRepository() {
            return Mockito.mock(OrderRepository.class);
        }

        @Bean
        public IdempotencyRepository idempotencyRepository() {
            IdempotencyRepository mock = Mockito.mock(IdempotencyRepository.class);
            when(mock.findById(any())).thenReturn(java.util.Optional.empty());
            when(mock.save(any())).thenAnswer(inv -> inv.getArgument(0));
            return mock;
        }

        @Bean
        public ShardCircuitBreakerService shardCircuitBreakerService(
                CircuitBreakerRegistry circuitBreakerRegistry,
                RetryRegistry retryRegistry) {
            return new ShardCircuitBreakerService(circuitBreakerRegistry, retryRegistry);
        }

        @Bean
        public CircuitBreakerRegistry circuitBreakerRegistry() {
            CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(4)
                    .failureRateThreshold(50f)
                    .waitDurationInOpenState(Duration.ofMillis(50))
                    .permittedNumberOfCallsInHalfOpenState(2)
                    .automaticTransitionFromOpenToHalfOpenEnabled(false)
                    .ignoreExceptions(OrderNotFoundException.class)
                    .build();
            return CircuitBreakerRegistry.of(cfg);
        }

        @Bean
        public RetryRegistry retryRegistry() {
            RetryConfig cfg = RetryConfig.custom()
                    .maxAttempts(1)                        // no retries in FVT
                    .waitDuration(Duration.ofMillis(0))
                    .build();
            return RetryRegistry.of(cfg);
        }
    }

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired OrderService              orderService;
    @Autowired OrderRepository           orderRepository;
    @Autowired ShardCircuitBreakerService circuitBreakerService;
    @Autowired CircuitBreakerRegistry    circuitBreakerRegistry;

    // ── Reset state before each test ──────────────────────────────────────────

    @BeforeEach
    void setup() {
        Mockito.reset(orderRepository);
        // Default: save returns the order with an assigned ID
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getOrderId() == null) o.setOrderId((long)(Math.random() * 100_000) + 1);
            return o;
        });
        // Reset all circuit breakers to CLOSED for test isolation
        for (int shard = 0; shard < 3; shard++) {
            resetBreaker(shard, Role.PRIMARY);
            resetBreaker(shard, Role.REPLICA);
        }
        ShardContextHolder.clear();
    }

    private void resetBreaker(int shard, Role role) {
        CircuitBreaker cb = circuitBreakerService.getCircuitBreaker(shard, role);
        cb.transitionToClosedState();
    }

    // ── FVT-CB-01: Normal operation succeeds through CB ───────────────────────

    @Test
    @DisplayName("FVT-CB-01: createOrder succeeds normally when circuit is CLOSED")
    void createOrder_succeedsWhenClosed() {
        Order result = orderService.createOrder(101L, BigDecimal.TEN, null).getOrder();
        assertThat(result).isNotNull();
        assertThat(circuitBreakerService.getState(2, Role.PRIMARY))
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── FVT-CB-02: Circuit trips on repeated repository failures ──────────────

    @Test
    @DisplayName("FVT-CB-02: Circuit for shard2-primary trips OPEN after repeated save failures")
    void circuit_tripsOpen_onRepeatedFailures() {
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new RuntimeException("PostgreSQL connection refused"));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> orderService.createOrder(101L, BigDecimal.TEN, null))
                    .isInstanceOf(RuntimeException.class);
        }

        assertThat(circuitBreakerService.getState(2, Role.PRIMARY))
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ── FVT-CB-03: Open circuit rejects without calling repository ────────────

    @Test
    @DisplayName("FVT-CB-03: Open circuit rejects createOrder without touching repository")
    void openCircuit_rejectsWithoutCallingRepository() {
        // Trip the circuit
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new RuntimeException("DB down"));
        for (int i = 0; i < 4; i++) {
            try { orderService.createOrder(101L, BigDecimal.TEN, null); } catch (Exception ignored) {}
        }

        reset(orderRepository); // reset so we can count invocations
        when(orderRepository.save(any())).thenReturn(buildSaved(101L));

        assertThatThrownBy(() -> orderService.createOrder(101L, BigDecimal.TEN, null))
                .isInstanceOf(CallNotPermittedException.class);

        verify(orderRepository, never()).save(any());
    }

    // ── FVT-CB-04: Read circuit trips independently from write circuit ─────────

    @Test
    @DisplayName("FVT-CB-04: Tripping shard2-primary does not affect shard2-replica read circuit")
    void writeCircuit_independentFromReadCircuit() {
        when(orderRepository.save(any())).thenThrow(new RuntimeException("write fail"));
        for (int i = 0; i < 4; i++) {
            try { orderService.createOrder(101L, BigDecimal.TEN, null); } catch (Exception ignored) {}
        }
        assertThat(circuitBreakerService.getState(2, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreakerService.getState(2, Role.REPLICA)).isEqualTo(CircuitBreaker.State.CLOSED);

        when(orderRepository.findByUserId(101L)).thenReturn(List.of());
        List<Order> result = orderService.getOrdersByUser(101L);
        assertThat(result).isNotNull();
    }

    // ── FVT-CB-05: Different shards are isolated ──────────────────────────────

    @Test
    @DisplayName("FVT-CB-05: Tripping shard2-primary does not affect shard0-primary")
    void differentShards_areIsolated() {
        when(orderRepository.save(any())).thenThrow(new RuntimeException("shard2 down"));
        for (int i = 0; i < 4; i++) {
            try { orderService.createOrder(101L, BigDecimal.TEN, null); } catch (Exception ignored) {}
        }
        assertThat(circuitBreakerService.getState(2, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreakerService.getState(0, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.CLOSED);

        reset(orderRepository);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getOrderId() == null) o.setOrderId(42L);
            return o;
        });
        Order result = orderService.createOrder(0L, BigDecimal.TEN, null).getOrder();
        assertThat(result).isNotNull();
    }

    // ── FVT-CB-06: OrderNotFoundException does NOT trip the circuit ───────────

    @Test
    @DisplayName("FVT-CB-06: OrderNotFoundException (ignored exception) does not count as a CB failure")
    void orderNotFound_doesNotCountAsFailure() {
        when(orderRepository.findById(any())).thenReturn(Optional.empty());

        for (int i = 0; i < 4; i++) {
            try { orderService.getOrderById(101L, 999L); } catch (OrderNotFoundException ignored) {}
        }

        assertThat(circuitBreakerService.getState(2, Role.REPLICA))
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── FVT-CB-07: Circuit recovery OPEN → HALF_OPEN → CLOSED ────────────────

    @Test
    @DisplayName("FVT-CB-07: Circuit recovers OPEN → HALF_OPEN → CLOSED after successful probe calls")
    void circuit_recovers_afterSuccessfulProbes() throws Exception {
        when(orderRepository.save(any())).thenThrow(new RuntimeException("DB down"));
        for (int i = 0; i < 4; i++) {
            try { orderService.createOrder(101L, BigDecimal.TEN, null); } catch (Exception ignored) {}
        }
        assertThat(circuitBreakerService.getState(2, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(100);
        circuitBreakerService.getCircuitBreaker(2, Role.PRIMARY).transitionToHalfOpenState();
        assertThat(circuitBreakerService.getState(2, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        reset(orderRepository);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getOrderId() == null) o.setOrderId(99L);
            return o;
        });
        orderService.createOrder(101L, BigDecimal.TEN, null);
        orderService.createOrder(101L, BigDecimal.TEN, null);

        assertThat(circuitBreakerService.getState(2, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Order buildSaved(long userId) {
        Order o = new Order(userId, BigDecimal.TEN);
        o.setOrderId(99L);
        o.setStatus("PENDING");
        return o;
    }
}
