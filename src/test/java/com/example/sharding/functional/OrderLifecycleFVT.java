package com.example.sharding.functional;

import com.example.sharding.entity.Order;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Functional Verification Test — Order Lifecycle
 *
 * <p>Verifies the complete observable behaviour of the order management flow:
 * create → read → update status → not-found error handling.
 * Each test exercises a real method call through the full AOP-wired service.
 */
@DisplayName("FVT: Order Lifecycle")
public class OrderLifecycleFVT extends FunctionalTestBase {

    @BeforeEach
    void setup() {
        resetMocks();
        stubSave();
    }

    // ── FVT-OL-01: Order creation produces a persisted, PENDING order ─────────

    @Test
    @DisplayName("FVT-OL-01: Created order is persisted with PENDING status and correct fields")
    void createdOrder_hasCorrectFieldValues() {
        Order result = orderService.createOrder(101L, new BigDecimal("250.00"));

        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isNotNull().isPositive();
        assertThat(result.getUserId()).isEqualTo(101L);
        assertThat(result.getAmount()).isEqualByComparingTo("250.00");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getCreatedAt()).isNotNull();
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    // ── FVT-OL-02: Reading orders returns repository data unchanged ───────────

    @Test
    @DisplayName("FVT-OL-02: getOrdersByUser returns all orders from repository unchanged")
    void getOrdersByUser_returnsRepositoryDataUnchanged() {
        List<Order> stored = List.of(
            order(1L, 101L, 100.00, "PENDING"),
            order(2L, 101L, 200.00, "SHIPPED")
        );
        stubFindByUserId(101L, stored);

        List<Order> result = orderService.getOrdersByUser(101L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getOrderId()).isEqualTo(1L);
        assertThat(result.get(1).getStatus()).isEqualTo("SHIPPED");
        verify(orderRepository, times(1)).findByUserId(101L);
    }

    // ── FVT-OL-03: getOrderById returns exact order by ID ────────────────────

    @Test
    @DisplayName("FVT-OL-03: getOrderById returns the exact order matching the given ID")
    void getOrderById_returnsExactOrder() {
        Order stored = order(42L, 101L, 75.50, "CONFIRMED");
        stubFindById(42L, stored);

        Order result = orderService.getOrderById(101L, 42L);

        assertThat(result.getOrderId()).isEqualTo(42L);
        assertThat(result.getAmount()).isEqualByComparingTo("75.50");
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
    }

    // ── FVT-OL-04: Status update mutates only the status field ───────────────

    @Test
    @DisplayName("FVT-OL-04: updateOrderStatus only changes the status field, preserves all others")
    void updateOrderStatus_onlyStatusChanges() {
        Order original = order(10L, 101L, 99.99, "PENDING");
        stubFindById(10L, original);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Order updated = orderService.updateOrderStatus(101L, 10L, "CONFIRMED");

        assertThat(updated.getStatus()).isEqualTo("CONFIRMED");
        assertThat(updated.getOrderId()).isEqualTo(10L);
        assertThat(updated.getUserId()).isEqualTo(101L);
        assertThat(updated.getAmount()).isEqualByComparingTo("99.99");
    }

    // ── FVT-OL-05: Full status lifecycle ─────────────────────────────────────

    @Test
    @DisplayName("FVT-OL-05: Order status can progress through the full lifecycle")
    void orderStatus_progressesThroughLifecycle() {
        Order order = order(5L, 101L, 150.00, "PENDING");
        stubFindById(5L, order);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String[] lifecycle = {"CONFIRMED", "SHIPPED", "DELIVERED"};
        for (String status : lifecycle) {
            Order updated = orderService.updateOrderStatus(101L, 5L, status);
            assertThat(updated.getStatus()).isEqualTo(status);
        }
    }

    // ── FVT-OL-06: getOrderById throws when order not found ──────────────────

    @Test
    @DisplayName("FVT-OL-06: getOrderById throws RuntimeException with meaningful message when not found")
    void getOrderById_notFound_throwsWithMessage() {
        stubFindByIdEmpty(999L);

        assertThatThrownBy(() -> orderService.getOrderById(101L, 999L))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Order not found")
            .hasMessageContaining("999");
    }

    // ── FVT-OL-07: updateOrderStatus throws when order not found ─────────────

    @Test
    @DisplayName("FVT-OL-07: updateOrderStatus throws RuntimeException when order not found")
    void updateOrderStatus_notFound_throwsWithMessage() {
        stubFindByIdEmpty(888L);

        assertThatThrownBy(() -> orderService.updateOrderStatus(101L, 888L, "SHIPPED"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Order not found");
    }

    // ── FVT-OL-08: getOrdersByUser returns empty list for unknown user ────────

    @Test
    @DisplayName("FVT-OL-08: getOrdersByUser returns empty list (not null) for unknown user")
    void getOrdersByUser_unknownUser_returnsEmptyList() {
        stubFindByUserId(777L, List.of());

        List<Order> result = orderService.getOrdersByUser(777L);

        assertThat(result).isNotNull().isEmpty();
    }

    // ── FVT-OL-09: Multiple creates call repository save each time ────────────

    @Test
    @DisplayName("FVT-OL-09: Creating N orders results in exactly N repository save calls")
    void multipleCreates_eachCallsRepositorySave() {
        int count = 5;
        for (int i = 0; i < count; i++) {
            orderService.createOrder(101L, BigDecimal.TEN);
        }
        verify(orderRepository, times(count)).save(any(Order.class));
    }

    // ── FVT-OL-10: Decimal amount precision is preserved ─────────────────────

    @Test
    @DisplayName("FVT-OL-10: Order amount with full decimal precision is stored exactly")
    void amountPrecision_isPreserved() {
        Order result = orderService.createOrder(101L, new BigDecimal("99.99"));
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("99.99"));
    }
}
