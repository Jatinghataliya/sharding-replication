package com.example.sharding.functional;

import com.example.sharding.entity.Order;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Functional Verification Test — Data Integrity
 *
 * <p>Verifies that data values are not corrupted or silently altered
 * as they pass through the service layer:
 * amount precision, timestamps, status values, userId binding,
 * and field immutability during status updates.
 */
@DisplayName("FVT: Data Integrity")
public class DataIntegrityFVT extends FunctionalTestBase {

    @BeforeEach
    void setup() {
        resetMocks();
        stubSave();
    }

    // ── FVT-DI-01: Amount with two decimal places is preserved ───────────────

    @Test
    @DisplayName("FVT-DI-01: Amount 0.01 (minimum currency unit) is stored exactly")
    void amount_minimumPrecision_preserved() {
        Order result = orderService.createOrder(101L, new BigDecimal("0.01"));
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("0.01"));
    }

    // ── FVT-DI-02: Large amount is preserved without truncation ──────────────

    @Test
    @DisplayName("FVT-DI-02: Large amount 99999999.99 is stored without truncation")
    void amount_largePrecision_preserved() {
        Order result = orderService.createOrder(101L, new BigDecimal("99999999.99"));
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("99999999.99"));
    }

    // ── FVT-DI-03: Default status is exactly "PENDING" ───────────────────────

    @Test
    @DisplayName("FVT-DI-03: Newly created order always has exactly the status string 'PENDING'")
    void newOrder_statusIsExactlyPending() {
        Order result = orderService.createOrder(101L, BigDecimal.TEN);
        assertThat(result.getStatus())
            .isEqualTo("PENDING")
            .isNotEqualTo("pending")    // case-sensitive check
            .isNotEqualTo("CREATED");
    }

    // ── FVT-DI-04: userId is bound correctly to the order ────────────────────

    @Test
    @DisplayName("FVT-DI-04: userId passed to createOrder is bound exactly to the order's userId field")
    void userId_boundCorrectlyToOrder() {
        long userId = 987654321L;
        Order result = orderService.createOrder(userId, BigDecimal.ONE);
        assertThat(result.getUserId()).isEqualTo(userId);
    }

    // ── FVT-DI-05: createdAt is populated and not in the future ──────────────

    @Test
    @DisplayName("FVT-DI-05: createdAt is populated and is not in the future")
    void createdAt_isPopulatedAndNotFuture() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        Order result = orderService.createOrder(101L, BigDecimal.TEN);
        LocalDateTime after  = LocalDateTime.now().plusSeconds(1);

        assertThat(result.getCreatedAt())
            .isNotNull()
            .isAfter(before)
            .isBefore(after);
    }

    // ── FVT-DI-06: Status update preserves all non-status fields ─────────────

    @Test
    @DisplayName("FVT-DI-06: updateOrderStatus preserves userId, amount, createdAt, orderId")
    void statusUpdate_preservesAllOtherFields() {
        LocalDateTime ts = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
        Order original = new Order(101L, new BigDecimal("250.00"));
        original.setOrderId(7L);
        original.setStatus("PENDING");
        original.setCreatedAt(ts);

        stubFindById(7L, original);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order updated = orderService.updateOrderStatus(101L, 7L, "SHIPPED");

        assertThat(updated.getOrderId()).isEqualTo(7L);
        assertThat(updated.getUserId()).isEqualTo(101L);
        assertThat(updated.getAmount()).isEqualByComparingTo("250.00");
        assertThat(updated.getCreatedAt()).isEqualTo(ts);
        assertThat(updated.getStatus()).isEqualTo("SHIPPED");
    }

    // ── FVT-DI-07: Multiple orders for same user have independent IDs ─────────

    @Test
    @DisplayName("FVT-DI-07: Multiple orders for the same user have distinct order IDs")
    void multipleOrders_haveDistinctIds() {
        Order o1 = orderService.createOrder(101L, new BigDecimal("10.00"));
        Order o2 = orderService.createOrder(101L, new BigDecimal("20.00"));
        assertThat(o1.getOrderId()).isNotEqualTo(o2.getOrderId());
    }

    // ── FVT-DI-08: Reading back stored list is never null ────────────────────

    @Test
    @DisplayName("FVT-DI-08: getOrdersByUser never returns null — always a list (possibly empty)")
    void getOrdersByUser_neverReturnsNull() {
        stubFindByUserId(101L, List.of());
        List<Order> result = orderService.getOrdersByUser(101L);
        assertThat(result).isNotNull();
    }

    // ── FVT-DI-09: Zero-amount order is accepted and stored ──────────────────

    @Test
    @DisplayName("FVT-DI-09: Zero-amount order is accepted without error")
    void zeroAmountOrder_isAccepted() {
        assertThatCode(() ->
            orderService.createOrder(101L, BigDecimal.ZERO)
        ).doesNotThrowAnyException();
    }

    // ── FVT-DI-10: Error message includes the missing order ID ───────────────

    @Test
    @DisplayName("FVT-DI-10: Not-found error message includes the specific order ID that was missing")
    void notFoundError_includesOrderId() {
        stubFindByIdEmpty(12345L);

        assertThatThrownBy(() -> orderService.getOrderById(101L, 12345L))
            .hasMessageContaining("12345");
    }
}
