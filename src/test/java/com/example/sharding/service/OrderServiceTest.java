package com.example.sharding.service;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.OrderRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.sharding.context.ShardContextHolder.Role.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderService}.
 * The repository is mocked — no Spring context or database required.
 * Shard routing state (ThreadLocal) is verified after each call.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService")
public class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    @AfterEach
    void clearContext() {
        ShardContextHolder.clear();
    }

    // ── createOrder ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("createOrder saves order with correct userId and amount")
    void createOrder_savesOrder() {
        long userId = 101L;
        BigDecimal amount = new BigDecimal("250.00");
        Order saved = buildOrder(1L, userId, amount, "PENDING");
        when(orderRepository.save(any(Order.class))).thenReturn(saved);

        Order result = orderService.createOrder(userId, amount);

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getAmount()).isEqualByComparingTo(amount);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("createOrder sets the correct shard index in context")
    void createOrder_setsCorrectShard() {
        long userId = 3L; // 3 % 3 = shard 0
        when(orderRepository.save(any())).thenReturn(buildOrder(1L, userId, BigDecimal.TEN, "PENDING"));

        orderService.createOrder(userId, BigDecimal.TEN);

        assertThat(ShardContextHolder.getShard())
                .isEqualTo(DataSourceConfig.resolveShardIndex(userId));
    }

    @Test
    @DisplayName("createOrder with userId=101 targets shard 2")
    void createOrder_userId101_targetsShard2() {
        long userId = 101L; // 101 % 3 = 2
        when(orderRepository.save(any())).thenReturn(buildOrder(1L, userId, BigDecimal.TEN, "PENDING"));

        orderService.createOrder(userId, BigDecimal.TEN);

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
        long userId = 10L;
        long orderId = 5L;
        Order order = buildOrder(orderId, userId, new BigDecimal("75.00"), "CONFIRMED");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        Order result = orderService.getOrderById(userId, orderId);

        assertThat(result.getOrderId()).isEqualTo(orderId);
        assertThat(result.getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("getOrderById throws RuntimeException when order not found")
    void getOrderById_notFound_throwsException() {
        long userId = 10L;
        long orderId = 999L;
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderById(userId, orderId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Order not found");
    }

    // ── updateOrderStatus ─────────────────────────────────────────────────────

    @Test
    @DisplayName("updateOrderStatus updates and returns the order")
    void updateOrderStatus_updatesOrder() {
        long userId = 4L;
        long orderId = 2L;
        Order existing = buildOrder(orderId, userId, new BigDecimal("50.00"), "PENDING");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(existing));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.updateOrderStatus(userId, orderId, "SHIPPED");

        assertThat(result.getStatus()).isEqualTo("SHIPPED");
        verify(orderRepository).save(existing);
    }

    @Test
    @DisplayName("updateOrderStatus throws RuntimeException when order not found")
    void updateOrderStatus_notFound_throwsException() {
        long userId = 4L;
        long orderId = 999L;
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.updateOrderStatus(userId, orderId, "SHIPPED"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    @DisplayName("updateOrderStatus sets the correct shard index in context")
    void updateOrderStatus_setsCorrectShard() {
        long userId = 6L; // 6 % 3 = 0
        long orderId = 1L;
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
        // userIds 0, 3, 6 all map to shard 0
        long[] shard0Users = {0L, 3L, 6L, 9L};
        for (long uid : shard0Users) {
            when(orderRepository.findByUserId(uid)).thenReturn(List.of());
            orderService.getOrdersByUser(uid);
            assertThat(ShardContextHolder.getShard()).isEqualTo(0);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Order buildOrder(long orderId, long userId, BigDecimal amount, String status) {
        Order o = new Order(userId, amount);
        o.setOrderId(orderId);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
