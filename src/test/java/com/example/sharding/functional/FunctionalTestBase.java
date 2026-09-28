package com.example.sharding.functional;

import com.example.sharding.aspect.TransactionRoutingAspect;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.entity.Order;
import com.example.sharding.idempotency.IdempotencyService;
import com.example.sharding.repository.IdempotencyRepository;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.service.OrderService;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Base class for all Functional Verification Tests (FVTs).
 *
 * <p>Provides a minimal Spring context containing:
 * <ul>
 *   <li>{@link OrderService} + {@link IdempotencyService} — classes under test</li>
 *   <li>{@link TransactionRoutingAspect} — AOP wiring for read/write splitting</li>
 *   <li>Mockito mocks for repositories — no real DB needed</li>
 * </ul>
 *
 * <p>Each FVT subclass gets a fresh Spring context and can call
 * {@link #resetMocks()} in {@code @BeforeEach} to guarantee isolation.
 */
@SpringJUnitConfig(FunctionalTestBase.Config.class)
public abstract class FunctionalTestBase {

    @TestConfiguration
    @EnableAspectJAutoProxy
    @Import({OrderService.class, TransactionRoutingAspect.class})
    static class Config {
        @Bean
        public OrderRepository orderRepository() {
            return Mockito.mock(OrderRepository.class);
        }

        @Bean
        public IdempotencyRepository idempotencyRepository() {
            return Mockito.mock(IdempotencyRepository.class);
        }

        @Bean
        public IdempotencyService idempotencyService(IdempotencyRepository idempotencyRepository,
                                                     OrderRepository orderRepository) {
            IdempotencyService svc = new IdempotencyService(idempotencyRepository, orderRepository);
            return svc;
        }
    }

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired protected OrderService          orderService;
    @Autowired protected OrderRepository       orderRepository;
    @Autowired protected IdempotencyRepository idempotencyRepository;

    // ── Context capture helpers ───────────────────────────────────────────────

    protected int     capturedShard = -1;
    protected ShardContextHolder.Role capturedRole  = null;

    // ── Setup helpers ─────────────────────────────────────────────────────────

    protected void resetMocks() {
        Mockito.reset(orderRepository, idempotencyRepository);
        // Stub idempotencyRepository to behave as if no prior key exists
        when(idempotencyRepository.findById(any())).thenReturn(java.util.Optional.empty());
        when(idempotencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        capturedShard = -1;
        capturedRole  = null;
        ShardContextHolder.clear();
    }

    /**
     * Stubs {@code save} so it assigns a fake ID and captures the shard/role
     * that are active <em>inside</em> the transactional AOP boundary.
     */
    protected void stubSave() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getOrderId() == null) o.setOrderId((long)(Math.random() * 100_000) + 1);
            capturedShard = ShardContextHolder.getShard() != null ? ShardContextHolder.getShard() : -1;
            capturedRole  = ShardContextHolder.getRole();
            return o;
        });
    }

    protected void stubFindByUserId(long userId, List<Order> result) {
        when(orderRepository.findByUserId(userId)).thenAnswer(inv -> {
            capturedShard = ShardContextHolder.getShard() != null ? ShardContextHolder.getShard() : -1;
            capturedRole  = ShardContextHolder.getRole();
            return result;
        });
    }

    protected void stubFindById(long orderId, Order order) {
        when(orderRepository.findById(orderId)).thenAnswer(inv -> {
            capturedShard = ShardContextHolder.getShard() != null ? ShardContextHolder.getShard() : -1;
            capturedRole  = ShardContextHolder.getRole();
            return Optional.ofNullable(order);
        });
    }

    protected void stubFindByIdEmpty(long orderId) {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());
    }

    // ── Object factory ────────────────────────────────────────────────────────

    protected static Order order(long id, long userId, double amount, String status) {
        Order o = new Order(userId, BigDecimal.valueOf(amount));
        o.setOrderId(id);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
