package com.example.sharding.resilience;

import com.example.sharding.context.DataSourceKey;
import com.example.sharding.context.ShardContextHolder.Role;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Wraps every shard database call through its own named
 * {@link CircuitBreaker} and {@link Retry} instance.
 *
 * <h3>Naming convention</h3>
 * Each of the 6 shard/role combinations has a dedicated breaker:
 * <pre>
 *   shard0-primary, shard0-replica
 *   shard1-primary, shard1-replica
 *   shard2-primary, shard2-replica
 * </pre>
 * These names match the keys in {@code application.yml} under
 * {@code resilience4j.circuitbreaker.instances} and
 * {@code resilience4j.retry.instances}.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>Calls succeed normally when the circuit is CLOSED.</li>
 *   <li>After {@code failureRateThreshold} failures, the breaker trips OPEN
 *       and immediately throws {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}.</li>
 *   <li>After {@code waitDurationInOpenState}, the breaker moves to HALF_OPEN
 *       and allows probe calls to test recovery.</li>
 *   <li>Transient failures are retried up to {@code maxAttempts} times with
 *       exponential back-off before the circuit-breaker failure is counted.</li>
 * </ul>
 */
@Service
public class ShardCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(ShardCircuitBreakerService.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry           retryRegistry;

    public ShardCircuitBreakerService(CircuitBreakerRegistry circuitBreakerRegistry,
                                      RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry          = retryRegistry;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Executes {@code operation} through the circuit breaker + retry chain
     * for the given shard index and role.
     *
     * @param shardIndex 0-based shard index
     * @param role       PRIMARY or REPLICA
     * @param operation  the callable DB operation
     * @param <T>        return type
     * @return result of the operation
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException
     *         if the circuit is OPEN (triggers 503 upstream)
     */
    public <T> T execute(int shardIndex, Role role, Callable<T> operation) {
        String name           = breakerName(shardIndex, role);
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(name);
        Retry          retry   = retryRegistry.retry(name);

        log.debug("CB [{}] state={} before call", name, breaker.getState());

        // Decorate: retry wraps the call first, then circuit breaker wraps retry
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(
                breaker,
                Retry.decorateSupplier(retry, () -> {
                    try {
                        return operation.call();
                    } catch (RuntimeException re) {
                        throw re;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
        );

        return decorated.get();
    }

    /**
     * Returns the current state of a named circuit breaker.
     *
     * @param shardIndex 0-based shard index
     * @param role       PRIMARY or REPLICA
     * @return CircuitBreaker.State (CLOSED, OPEN, HALF_OPEN, DISABLED, FORCED_OPEN)
     */
    public CircuitBreaker.State getState(int shardIndex, Role role) {
        return circuitBreakerRegistry.circuitBreaker(breakerName(shardIndex, role)).getState();
    }

    /**
     * Returns the raw {@link CircuitBreaker} for a given shard/role — useful
     * for tests that need to manipulate state directly.
     */
    public CircuitBreaker getCircuitBreaker(int shardIndex, Role role) {
        return circuitBreakerRegistry.circuitBreaker(breakerName(shardIndex, role));
    }

    /**
     * Returns the {@link Retry} instance for a given shard/role.
     */
    public Retry getRetry(int shardIndex, Role role) {
        return retryRegistry.retry(breakerName(shardIndex, role));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the canonical name for a shard/role pair:
     * {@code "shard{N}-primary"} or {@code "shard{N}-replica"}.
     */
    public static String breakerName(int shardIndex, Role role) {
        return "shard" + shardIndex + "-" + role.name().toLowerCase();
    }

    /**
     * Convenience overload taking a {@link DataSourceKey}.
     */
    public static String breakerName(DataSourceKey key) {
        return breakerName(key.getShardIndex(), key.getRole());
    }
}
