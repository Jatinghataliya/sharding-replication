package com.example.sharding.service;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import com.example.sharding.entity.Order;
import com.example.sharding.exception.OrderNotFoundException;
import com.example.sharding.idempotency.IdempotencyService;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.resilience.ShardCircuitBreakerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderService}.
 * All dependencies are mocked — no Spring context or database required.
 * ShardCircuitBreakerService is stubbed to be a transparent pass-through
 * so tests focus on business logic / routing, not circuit-breaker mechanics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService")
public class OrderServiceTest {

    @Mock  private OrderRepository            orderRepository;
    @Mock  private IdempotencyService         idempotencyService;
    @Mock  private ShardCircuitBreakerService circuitBreakerService;
    @InjectMocks private OrderService         orderService;

    /** Make the circuit breaker a transparent pass-through for all unit tests. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubCircuitBreaker() throws Exception {
        when(circuitBreakerService.execute(anyInt(), any(Role.class), any(Callable.class)))
                .thenAnswer(inv -> {
                    Callable<?> op = inv.getArgument(2);
                    return op.call();
                });
    }

    @AfterEach
    void clearContext() { ShardContextHolder.clear(); }

    // ── createOrder ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("createOrder saves order with correct userId and amount")
    void createOrder_savesOrder() {
        long userId = 101L;
        BigDecimal amount = new BigDecimal("250.00");
        Order saved = buildOrder(1L, userId, amount, "PENDING");
        when(orderRepository.save(any(Order.class))).thenReturn(saved);

        OrderService.OrderResult result = orderService.createOrder(userId, amount, null);

        assertThat(result.getOrder().getUserId()).isEqualTo(userId);
        assertThat(result.getOrder().getAmount()).isEqualByComparingTo(amount);
        assertThat(result.getOrder().getStatus()).isEqualTo("PENDING");
        assertThat(result.isReplay()).isFalse();
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("createOrder sets the correct shard index in context")
    void createOrder_setsCorrectShard() {
        long userId = 3L; // 3 % 3 = shard 0
        when(orderRepository.save(any())).thenReturn(buildOrder(1L, userId, BigDecimal.TEN, "PENDING"));

        orderService.createOrder(userId, BigDecimal.TEN, null);

        assertThat(ShardContextHolder.getShard())
                .isEqualTo(DataSourceConfig.resolveShardIndex(userId));
    }

    @Test
    @DisplayName("createOrder with userId=101 targets shard 2")
    void createOrder_userId101_targetsShard2() {
        long userId = 101L;
        when(orderRepository.save(any())).thenReturn(buildOrder(1L, userId, BigDecimal.TEN, "PENDING"));

        orderService.createOrder(userId, BigDecimal.TEN, null);

        assertThat(ShardContextHolder.getShard()).isEqualTo(2);
    }

    // ── getOrdersByUser ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrdersByUser returns all orders from repository")
    void getOrdersByUser_returnsOrders() {
        long userId = 5L;
        List<Order> orders = List.of(
                buildOrder(1L, userId, new BigDecimal("100.00"), "PENDING"),
                buildOrder(2L, userId, new BigDecimal("200.00"), "SHIPPED")
        );
        when(orderRepository.findByUserId(userId)).thenReturn(orders);

        List<Order> result = orderService.getOrdersByUser(userId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Order::getUserId).containsOnly(userId);
        verify(orderRepository).findByUserId(userId);
    }

    @Test
    @DisplayName("getOrdersByUser returns empty list when no orders exist")
    void getOrdersByUser_returnsEmptyList() {
        long userId = 99L;
        when(orderRepository.findByUserId(userId)).thenReturn(List.of());

        List<Order> result = orderService.getOrdersByUser(userId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getOrdersByUser sets correct shard index in context")
    void getOrdersByUser_setsCorrectShard() {
        long userId = 7L; // 7 % 3 = 1
        when(orderRepository.findByUserId(userId)).thenReturn(List.of());

        orderService.getOrdersByUser(userId);

        assertThat(ShardContextHolder.getShard()).isEqualTo(1);
    }

    // ── getOrderById ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrderById returns the order when found")
    void getOrderById_found() {
        long userId = 10L, orderId = 5L;
        Order order = buildOrder(orderId, userId, new BigDecimal("75.00"), "CONFIRMED");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        Order result = orderService.getOrderById(userId, orderId);

        assertThat(result.getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("getOrderById throws OrderNotFoundException when order not found")
    void getOrderById_notFound_throwsException() {
        long userId = 10L, orderId = 999L;
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderById(userId, orderId))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("Order not found");
    }

    // ── updateOrderStatus ─────────────────────────────────────────────────────

    @Test
    @DisplayName("updateOrderStatus updates and returns the order")
    void updateOrderStatus_updatesOrder() {
        long userId = 4L, orderId = 2L;
        Order existing = buildOrder(orderId, userId, new BigDecimal("50.00"), "PENDING");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(existing));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.updateOrderStatus(userId, orderId, "SHIPPED");

        assertThat(result.getStatus()).isEqualTo("SHIPPED");
        verify(orderRepository).save(existing);
    }

    @Test
    @DisplayName("updateOrderStatus throws OrderNotFoundException when order not found")
    void updateOrderStatus_notFound_throwsException() {
        long userId = 4L, orderId = 999L;
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.updateOrderStatus(userId, orderId, "SHIPPED"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    @DisplayName("updateOrderStatus sets the correct shard index in context")
    void updateOrderStatus_setsCorrectShard() {
        long userId = 6L, orderId = 1L; // 6 % 3 = 0
        Order order = buildOrder(orderId, userId, BigDecimal.TEN, "PENDING");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenReturn(order);

        orderService.updateOrderStatus(userId, orderId, "CONFIRMED");

        assertThat(ShardContextHolder.getShard()).isEqualTo(0);
    }

    // ── Shard distribution consistency ───────────────────────────────────────

    @Test
    @DisplayName("Different userIds that map to the same shard all route correctly")
    void shardConsistency_sameShard() {
        long[] shard0Users = {0L, 3L, 6L, 9L};
        for (long uid : shard0Users) {
            when(orderRepository.findByUserId(uid)).thenReturn(List.of());
            orderService.getOrdersByUser(uid);
            assertThat(ShardContextHolder.getShard()).isEqualTo(0);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Order buildOrder(long orderId, long userId, BigDecimal amount, String status) {
        Order o = new Order(userId, amount);
        o.setOrderId(orderId);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
