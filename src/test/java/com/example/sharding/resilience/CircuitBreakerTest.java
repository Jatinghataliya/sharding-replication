package com.example.sharding.resilience;

import com.example.sharding.context.ShardContextHolder.Role;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ShardCircuitBreakerService}.
 *
 * <p>Uses programmatically created {@link CircuitBreakerRegistry} and
 * {@link RetryRegistry} instances so tests run in milliseconds
 * (no Spring context, no DB, no waiting for real timeouts).
 *
 * <p>The circuit-breaker config used here is deliberately aggressive
 * (window=4, threshold=50%, wait=0ms) so we can trip and observe
 * state transitions in a single test method.
 */
@DisplayName("ShardCircuitBreakerService — Unit Tests")
public class CircuitBreakerTest {

    // ── Shared registries with test-friendly config ───────────────────────────

    private static final CircuitBreakerConfig CB_CONFIG = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(4)
            .failureRateThreshold(50f)           // trip after 2/4 failures
            .waitDurationInOpenState(Duration.ofMillis(100))
            .permittedNumberOfCallsInHalfOpenState(2)
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .build();

    private static final RetryConfig RETRY_CONFIG = RetryConfig.custom()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(0))  // no actual waiting in tests
            .retryExceptions(SQLException.class)
            .build();

    private CircuitBreakerRegistry cbRegistry;
    private RetryRegistry          retryRegistry;
    private ShardCircuitBreakerService service;

    @BeforeEach
    void setUp() {
        cbRegistry    = CircuitBreakerRegistry.of(CB_CONFIG);
        retryRegistry = RetryRegistry.of(RETRY_CONFIG);
        service       = new ShardCircuitBreakerService(cbRegistry, retryRegistry);
    }

    // ── CB-01: Successful call passes through ─────────────────────────────────

    @Test
    @DisplayName("CB-01: Successful operation is executed and result is returned")
    void successfulCall_returnsResult() {
        String result = service.execute(0, Role.PRIMARY, () -> "hello");
        assertThat(result).isEqualTo("hello");
    }

    // ── CB-02: Circuit starts CLOSED ─────────────────────────────────────────

    @Test
    @DisplayName("CB-02: Circuit breaker starts in CLOSED state")
    void initialState_isClosed() {
        CircuitBreaker.State state = service.getState(0, Role.PRIMARY);
        assertThat(state).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── CB-03: Failures increment the failure counter ─────────────────────────

    @Test
    @DisplayName("CB-03: Runtime exceptions are propagated and counted as failures")
    void failingCall_propagatesException() {
        assertThatThrownBy(() ->
            service.execute(1, Role.REPLICA, () -> { throw new RuntimeException("DB down"); })
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("DB down");
    }

    // ── CB-04: Circuit trips OPEN after threshold ─────────────────────────────

    @Test
    @DisplayName("CB-04: Circuit trips OPEN after failure rate exceeds threshold")
    void circuit_tripsOpen_afterEnoughFailures() {
        // Fill the sliding window with failures (4 calls, 100% failure = trips at 50%)
        for (int i = 0; i < 4; i++) {
            try {
                service.execute(0, Role.PRIMARY, () -> { throw new RuntimeException("fail"); });
            } catch (Exception ignored) {}
        }

        assertThat(service.getState(0, Role.PRIMARY))
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ── CB-05: OPEN circuit rejects calls immediately ─────────────────────────

    @Test
    @DisplayName("CB-05: Open circuit immediately throws CallNotPermittedException without calling the operation")
    void openCircuit_rejectsCallsImmediately() {
        // Trip the circuit
        for (int i = 0; i < 4; i++) {
            try {
                service.execute(0, Role.PRIMARY, () -> { throw new RuntimeException("fail"); });
            } catch (Exception ignored) {}
        }

        AtomicInteger callCount = new AtomicInteger(0);
        assertThatThrownBy(() ->
            service.execute(0, Role.PRIMARY, () -> { callCount.incrementAndGet(); return "x"; })
        ).isInstanceOf(CallNotPermittedException.class);

        // The operation should NOT have been called
        assertThat(callCount.get()).isZero();
    }

    // ── CB-06: Different shard/role have independent breakers ─────────────────

    @Test
    @DisplayName("CB-06: Tripping shard0-primary does not affect shard1-replica")
    void differentShards_haveIndependentBreakers() {
        // Trip shard 0 primary
        for (int i = 0; i < 4; i++) {
            try {
                service.execute(0, Role.PRIMARY, () -> { throw new RuntimeException("fail"); });
            } catch (Exception ignored) {}
        }
        assertThat(service.getState(0, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.OPEN);

        // shard 1 replica should still be CLOSED
        assertThat(service.getState(1, Role.REPLICA)).isEqualTo(CircuitBreaker.State.CLOSED);

        // And shard 1 replica calls should succeed
        String result = service.execute(1, Role.REPLICA, () -> "still works");
        assertThat(result).isEqualTo("still works");
    }

    // ── CB-07: All 6 breakers start CLOSED ───────────────────────────────────

    @Test
    @DisplayName("CB-07: All 6 shard/role combinations start with CLOSED circuit")
    void allSixBreakers_startClosed() {
        for (int shard = 0; shard < 3; shard++) {
            assertThat(service.getState(shard, Role.PRIMARY))
                    .as("shard%d-primary", shard)
                    .isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(service.getState(shard, Role.REPLICA))
                    .as("shard%d-replica", shard)
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    // ── CB-08: Successful calls after tripping re-close the circuit ───────────

    @Test
    @DisplayName("CB-08: Circuit transitions OPEN → HALF_OPEN → CLOSED after successful probe calls")
    void circuit_recovers_afterSuccessfulProbes() throws Exception {
        // Trip the circuit
        for (int i = 0; i < 4; i++) {
            try {
                service.execute(0, Role.PRIMARY, () -> { throw new RuntimeException("fail"); });
            } catch (Exception ignored) {}
        }
        assertThat(service.getState(0, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait for the wait duration to expire, then force HALF_OPEN
        Thread.sleep(150);
        service.getCircuitBreaker(0, Role.PRIMARY).transitionToHalfOpenState();
        assertThat(service.getState(0, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Two successful probe calls should close the circuit
        service.execute(0, Role.PRIMARY, () -> "probe1");
        service.execute(0, Role.PRIMARY, () -> "probe2");

        assertThat(service.getState(0, Role.PRIMARY)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── CB-09: Breaker name convention ───────────────────────────────────────

    @Test
    @DisplayName("CB-09: Breaker names follow 'shard{N}-{role}' convention")
    void breakerNames_followConvention() {
        assertThat(ShardCircuitBreakerService.breakerName(0, Role.PRIMARY)).isEqualTo("shard0-primary");
        assertThat(ShardCircuitBreakerService.breakerName(1, Role.REPLICA)).isEqualTo("shard1-replica");
        assertThat(ShardCircuitBreakerService.breakerName(2, Role.PRIMARY)).isEqualTo("shard2-primary");
    }

    // ── CB-10: Retry is configured and accessible per shard ───────────────────

    @Test
    @DisplayName("CB-10: getRetry() returns the named Retry instance for the shard/role")
    void getRetry_returnsNamedInstance() {
        io.github.resilience4j.retry.Retry retry0p = service.getRetry(0, Role.PRIMARY);
        io.github.resilience4j.retry.Retry retry1r = service.getRetry(1, Role.REPLICA);

        assertThat(retry0p).isNotNull();
        assertThat(retry1r).isNotNull();
        assertThat(retry0p.getName()).isEqualTo("shard0-primary");
        assertThat(retry1r.getName()).isEqualTo("shard1-replica");
        // Different shards have different Retry instances
        assertThat(retry0p).isNotSameAs(retry1r);
    }

    // ── CB-11: Non-retryable exception is NOT retried ─────────────────────────

    @Test
    @DisplayName("CB-11: Non-retryable RuntimeException is NOT retried (called only once)")
    void nonRetryableException_isNotRetried() {
        AtomicInteger callCount = new AtomicInteger(0);

        assertThatThrownBy(() ->
            service.execute(0, Role.PRIMARY, () -> {
                callCount.incrementAndGet();
                throw new RuntimeException("business error");
            })
        ).isInstanceOf(RuntimeException.class);

        // Not in retryExceptions list → only called once
        assertThat(callCount.get()).isEqualTo(1);
    }

    // ── CB-12: getCircuitBreaker returns the same instance ───────────────────

    @Test
    @DisplayName("CB-12: getCircuitBreaker returns the same instance on repeated calls (registry singleton)")
    void getCircuitBreaker_returnsSameInstance() {
        CircuitBreaker cb1 = service.getCircuitBreaker(2, Role.REPLICA);
        CircuitBreaker cb2 = service.getCircuitBreaker(2, Role.REPLICA);
        assertThat(cb1).isSameAs(cb2);
    }

    // ── CB-13: CallNotPermittedException carries circuit breaker name ─────────

    @Test
    @DisplayName("CB-13: CallNotPermittedException message contains the circuit breaker name")
    void callNotPermitted_message_containsCircuitName() {
        for (int i = 0; i < 4; i++) {
            try {
                service.execute(2, Role.REPLICA, () -> { throw new RuntimeException("fail"); });
            } catch (Exception ignored) {}
        }

        assertThatThrownBy(() ->
            service.execute(2, Role.REPLICA, () -> "x")
        ).isInstanceOf(CallNotPermittedException.class)
         .hasMessageContaining("shard2-replica");
    }
}
