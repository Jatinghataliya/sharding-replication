package com.example.sharding.behaviour;

import com.example.sharding.aspect.TransactionRoutingAspect;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.entity.Order;
import com.example.sharding.repository.OrderRepository;
import com.example.sharding.service.OrderService;
import io.cucumber.spring.CucumberContextConfiguration;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Shared Cucumber world state — one instance per scenario (Cucumber prototype scope).
 *
 * <p>Uses a minimal Spring context containing only:
 * <ul>
 *   <li>{@link OrderService} — the class under test</li>
 *   <li>{@link TransactionRoutingAspect} — AOP weaving for role detection</li>
 *   <li>A Mockito mock for {@link OrderRepository}</li>
 * </ul>
 * No DataSource, no JPA, no PostgreSQL connection needed.
 * </p>
 */
@CucumberContextConfiguration
@SpringJUnitConfig(CucumberSpringContext.Config.class)
public class CucumberSpringContext {

    // ── Minimal Spring context ────────────────────────────────────────────────

    @TestConfiguration
    @EnableAspectJAutoProxy
    @Import({OrderService.class, TransactionRoutingAspect.class})
    static class Config {
        @Bean
        public OrderRepository orderRepository() {
            return Mockito.mock(OrderRepository.class);
        }
    }

    // ── Spring beans under test ───────────────────────────────────────────────

    public final OrderService orderService;
    public final OrderRepository orderRepository;

    // ── Scenario state ────────────────────────────────────────────────────────

    public Order lastCreatedOrder;
    public Order lastFetchedOrder;
    public List<Order> lastFetchedList   = new ArrayList<>();
    public Exception   thrownException;
    public List<Order> allCreatedOrders  = new ArrayList<>();

    // Shard index and role captured at the moment the repository mock was called
    // (i.e. while the AOP aspect is still active, before it clears the context)
    public int capturedShard  = -1;
    public ShardContextHolder.Role capturedRole = null;
    // flag set by Given steps that pre-stub findByUserId with real data
    public boolean findByUserIdAlreadyStubbed = false;

    public CucumberSpringContext(OrderService orderService, OrderRepository orderRepository) {
        this.orderService   = orderService;
        this.orderRepository = orderRepository;
    }

    // ── Mock helpers ──────────────────────────────────────────────────────────

    /**
     * Stubs {@code save} to return the order with an assigned ID, while capturing
     * the shard + role that are active inside the AOP transaction boundary.
     */
    public void stubSaveCapturingContext() {
        Mockito.reset(orderRepository);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getOrderId() == null) {
                o.setOrderId((long)(Math.random() * 100_000) + 1);
            }
            // Capture context while still inside the transactional AOP boundary
            capturedShard = ShardContextHolder.getShard() != null
                    ? ShardContextHolder.getShard() : -1;
            capturedRole  = ShardContextHolder.getRole();
            return o;
        });
    }

    /** Stubs findByUserId capturing context inside the AOP boundary, and sets the flag. */
    public void stubFindByUserIdCapturingContext(long userId, List<Order> result) {
        findByUserIdAlreadyStubbed = true;
        when(orderRepository.findByUserId(userId)).thenAnswer(inv -> {
            capturedShard = ShardContextHolder.getShard() != null
                    ? ShardContextHolder.getShard() : -1;
            capturedRole  = ShardContextHolder.getRole();
            return result;
        });
    }

    /** Stubs findById capturing context inside the AOP boundary. */
    public void stubFindByIdCapturingContext(long orderId, Order order) {
        when(orderRepository.findById(orderId)).thenAnswer(inv -> {
            capturedShard = ShardContextHolder.getShard() != null
                    ? ShardContextHolder.getShard() : -1;
            capturedRole  = ShardContextHolder.getRole();
            return java.util.Optional.ofNullable(order);
        });
    }

    /** Convenience builder. */
    public static Order buildOrder(long orderId, long userId, double amount, String status) {
        Order o = new Order(userId, BigDecimal.valueOf(amount));
        o.setOrderId(orderId);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }

    /** Resets scenario-scoped mutable state before each scenario. */
    public void reset() {
        lastCreatedOrder = null;
        lastFetchedOrder = null;
        lastFetchedList  = new ArrayList<>();
        thrownException              = null;
        allCreatedOrders             = new ArrayList<>();
        capturedShard                = -1;
        capturedRole                 = null;
        findByUserIdAlreadyStubbed   = false;
        Mockito.reset(orderRepository);
    }
}
