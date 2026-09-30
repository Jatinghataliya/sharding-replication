package com.example.sharding.idempotency;

import com.example.sharding.aspect.TransactionRoutingAspect;
import com.example.sharding.context.ShardContextHolder.Role;
import com.example.sharding.entity.IdempotencyRecord;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.IdempotencyRepository;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.resilience.ShardCircuitBreakerService;
import com.example.sharding.service.OrderService;
import com.example.sharding.service.OrderService.OrderResult;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Idempotency Tests — verifies the full idempotency contract:
 * <ul>
 *   <li>First request with a key → creates order, returns 201-equivalent</li>
 *   <li>Retry with same key (within TTL) → returns cached order, replay=true</li>
 *   <li>Expired key → treated as new request</li>
 *   <li>No key → not idempotent, always creates</li>
 *   <li>Invalid key → throws 400-equivalent</li>
 * </ul>
 */
@SpringJUnitConfig(IdempotencyTest.Config.class)
@DisplayName("Idempotency Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IdempotencyTest {

    @TestConfiguration
    @EnableAspectJAutoProxy
    @Import({OrderService.class, TransactionRoutingAspect.class, IdempotencyService.class})
    static class Config {
        @Bean public OrderRepository orderRepository()            { return Mockito.mock(OrderRepository.class); }
        @Bean public IdempotencyRepository idempotencyRepository(){ return Mockito.mock(IdempotencyRepository.class); }

        @Bean
        @SuppressWarnings("unchecked")
        public ShardCircuitBreakerService shardCircuitBreakerService() throws Exception {
            ShardCircuitBreakerService mock = Mockito.mock(ShardCircuitBreakerService.class);
            when(mock.execute(anyInt(), any(Role.class), any(Callable.class)))
                    .thenAnswer(inv -> { Callable<?> op = inv.getArgument(2); return op.call(); });
            return mock;
        }
    }

    @Autowired OrderService          orderService;
    @Autowired OrderRepository       orderRepository;
    @Autowired IdempotencyRepository idempotencyRepository;
    @Autowired IdempotencyService    idempotencyService;

    @BeforeEach
    void resetAll() {
        Mockito.reset(orderRepository, idempotencyRepository);
        // Default save stub
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getOrderId() == null) o.setOrderId((long)(Math.random() * 100_000) + 1);
            return o;
        });
        when(idempotencyRepository.save(any(IdempotencyRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyRepository.findById(any())).thenReturn(Optional.empty());
    }

    // ── IT-01: First request creates a new order ──────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("IT-01: First request with a new key creates the order and returns isReplay=false")
    void firstRequest_createsOrder_isReplayFalse() {
        String key = UUID.randomUUID().toString();

        OrderResult result = orderService.createOrder(101L, new BigDecimal("100.00"), key);

        assertThat(result.isReplay()).isFalse();
        assertThat(result.getOrder()).isNotNull();
        assertThat(result.getOrder().getIdempotencyKey()).isEqualTo(key);
        verify(orderRepository, times(1)).save(any(Order.class));
        verify(idempotencyRepository, times(1)).save(any(IdempotencyRecord.class));
    }

    // ── IT-02: Duplicate request returns the same order ───────────────────────

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("IT-02: Second request with same key returns cached order and isReplay=true")
    void duplicateRequest_returnsCachedOrder_isReplayTrue() {
        String key   = UUID.randomUUID().toString();
        Order  saved = buildOrder(42L, 101L, 100.00, key);

        // Simulate an existing record
        IdempotencyRecord record = new IdempotencyRecord(
                key, 101L, 42L, LocalDateTime.now().plusHours(24));
        when(idempotencyRepository.findById(key)).thenReturn(Optional.of(record));
        when(orderRepository.findById(42L)).thenReturn(Optional.of(saved));

        OrderResult result = orderService.createOrder(101L, new BigDecimal("100.00"), key);

        assertThat(result.isReplay()).isTrue();
        assertThat(result.getOrder().getOrderId()).isEqualTo(42L);
        // Repository save must NOT be called again
        verify(orderRepository, never()).save(any(Order.class));
    }

    // ── IT-03: Expired key creates a new order ────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("IT-03: Expired idempotency key is ignored — new order is created")
    void expiredKey_createsNewOrder() {
        String key = UUID.randomUUID().toString();

        // Expired record
        IdempotencyRecord expired = new IdempotencyRecord(
                key, 101L, 10L, LocalDateTime.now().minusHours(1)); // already expired
        when(idempotencyRepository.findById(key)).thenReturn(Optional.of(expired));

        OrderResult result = orderService.createOrder(101L, new BigDecimal("50.00"), key);

        assertThat(result.isReplay()).isFalse();
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    // ── IT-04: No key — always creates a new order ────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("IT-04: Request without Idempotency-Key always creates a new order")
    void noKey_alwaysCreatesNewOrder() {
        OrderResult r1 = orderService.createOrder(101L, new BigDecimal("10.00"), null);
        OrderResult r2 = orderService.createOrder(101L, new BigDecimal("10.00"), null);

        assertThat(r1.isReplay()).isFalse();
        assertThat(r2.isReplay()).isFalse();
        verify(orderRepository, times(2)).save(any(Order.class));
        verify(idempotencyRepository, never()).save(any(IdempotencyRecord.class));
    }

    // ── IT-05: Blank key — treated as no key ──────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("IT-05: Blank Idempotency-Key is treated as no key — order is created without idempotency")
    void blankKey_treatedAsNoKey() {
        OrderResult result = orderService.createOrder(101L, BigDecimal.TEN, "   ");

        assertThat(result.isReplay()).isFalse();
        verify(idempotencyRepository, never()).findById(any());
        verify(idempotencyRepository, never()).save(any(IdempotencyRecord.class));
    }

    // ── IT-06: Key too long — throws IllegalArgumentException (→ 400) ─────────

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("IT-06: Idempotency-Key longer than 64 characters throws IllegalArgumentException")
    void keyTooLong_throwsIllegalArgumentException() {
        String longKey = "a".repeat(65);

        assertThatThrownBy(() -> orderService.createOrder(101L, BigDecimal.TEN, longKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 characters");
    }

    // ── IT-07: Idempotency record has correct TTL ──────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("IT-07: Saved idempotency record has expiresAt set to 24 hours in the future")
    void savedRecord_hasCorrectTtl() {
        ReflectionTestUtils.setField(idempotencyService, "ttlHours", 24);
        String key = UUID.randomUUID().toString();

        LocalDateTime before = LocalDateTime.now().plusHours(23).plusMinutes(59);
        orderService.createOrder(101L, BigDecimal.TEN, key);
        LocalDateTime after  = LocalDateTime.now().plusHours(24).plusMinutes(1);

        verify(idempotencyRepository).save(argThat(r ->
                r.getExpiresAt().isAfter(before) && r.getExpiresAt().isBefore(after)
        ));
    }

    // ── IT-08: Replay returns exact same orderId ───────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("IT-08: Replayed order has the exact same orderId as the original")
    void replayReturnsSameOrderId() {
        String key   = UUID.randomUUID().toString();
        long   origId = 99L;
        Order  saved  = buildOrder(origId, 101L, 200.00, key);

        IdempotencyRecord record = new IdempotencyRecord(
                key, 101L, origId, LocalDateTime.now().plusHours(24));
        when(idempotencyRepository.findById(key)).thenReturn(Optional.of(record));
        when(orderRepository.findById(origId)).thenReturn(Optional.of(saved));

        OrderResult result = orderService.createOrder(101L, new BigDecimal("999.00"), key);

        assertThat(result.getOrder().getOrderId()).isEqualTo(origId);
        assertThat(result.getOrder().getAmount()).isEqualByComparingTo("200.00"); // original amount
    }

    // ── IT-09: isExpired() logic on IdempotencyRecord ─────────────────────────

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("IT-09: IdempotencyRecord.isExpired() returns true past expiresAt, false before it")
    void idempotencyRecord_isExpired_correct() {
        IdempotencyRecord active  = new IdempotencyRecord("k1", 1L, 1L,
                LocalDateTime.now().plusHours(1));
        IdempotencyRecord expired = new IdempotencyRecord("k2", 1L, 2L,
                LocalDateTime.now().minusSeconds(1));

        assertThat(active.isExpired()).isFalse();
        assertThat(expired.isExpired()).isTrue();
    }

    // ── IT-10: Key stored on idempotency_key field in Order ───────────────────

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("IT-10: Created order carries the idempotency key in its idempotencyKey field")
    void createdOrder_carriesIdempotencyKey() {
        String key = UUID.randomUUID().toString();

        OrderResult result = orderService.createOrder(101L, BigDecimal.TEN, key);

        assertThat(result.getOrder().getIdempotencyKey()).isEqualTo(key);
    }

    // ── IT-11: Two different keys produce two independent orders ──────────────

    @Test
    @org.junit.jupiter.api.Order(11)
    @DisplayName("IT-11: Two requests with different keys each create their own order")
    void differentKeys_createIndependentOrders() {
        String key1 = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();

        OrderResult r1 = orderService.createOrder(101L, new BigDecimal("10.00"), key1);
        OrderResult r2 = orderService.createOrder(101L, new BigDecimal("20.00"), key2);

        assertThat(r1.isReplay()).isFalse();
        assertThat(r2.isReplay()).isFalse();
        assertThat(r1.getOrder().getOrderId()).isNotEqualTo(r2.getOrder().getOrderId());
        verify(orderRepository, times(2)).save(any(Order.class));
    }

    // ── IT-12: validateKey throws on null key ─────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(12)
    @DisplayName("IT-12: IdempotencyService.validateKey throws on null input")
    void validateKey_throwsOnNull() {
        assertThatThrownBy(() -> idempotencyService.validateKey(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    // ── IT-13: Controller returns HTTP 200 for replay, 201 for new ────────────

    @Test
    @org.junit.jupiter.api.Order(13)
    @DisplayName("IT-13: OrderResult.isReplay() drives correct HTTP status selection")
    void orderResult_replayFlag_drivesHttpStatus() {
        Order o = buildOrder(1L, 101L, 50.00, null);

        OrderResult created = OrderResult.created(o);
        OrderResult replay  = OrderResult.replay(o);

        assertThat(created.isReplay()).isFalse();
        assertThat(replay.isReplay()).isTrue();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Order buildOrder(long id, long userId, double amount, String key) {
        Order o = new Order(userId, BigDecimal.valueOf(amount));
        o.setOrderId(id);
        o.setStatus("PENDING");
        o.setCreatedAt(LocalDateTime.now());
        o.setIdempotencyKey(key);
        return o;
    }
}
