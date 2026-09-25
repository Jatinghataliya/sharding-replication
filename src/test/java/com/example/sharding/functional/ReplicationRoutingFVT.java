package com.example.sharding.functional;

import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import com.example.sharding.entity.Order;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Functional Verification Test — Replication Routing (Read/Write Splitting)
 *
 * <p>Verifies that every write operation routes to PRIMARY and every
 * read operation routes to REPLICA. Also verifies that the role is
 * properly isolated — each operation starts clean and leaves clean.
 */
@DisplayName("FVT: Replication Routing — Read/Write Split")
public class ReplicationRoutingFVT extends FunctionalTestBase {

    @BeforeEach
    void setup() {
        resetMocks();
        stubSave();
    }

    // ── FVT-RR-01: createOrder uses PRIMARY role ──────────────────────────────

    @Test
    @DisplayName("FVT-RR-01: createOrder routes to PRIMARY (write operation)")
    void createOrder_routesToPrimary() {
        orderService.createOrder(101L, BigDecimal.TEN);
        assertThat(capturedRole).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-RR-02: updateOrderStatus uses PRIMARY role ────────────────────────

    @Test
    @DisplayName("FVT-RR-02: updateOrderStatus routes to PRIMARY (write operation)")
    void updateOrderStatus_routesToPrimary() {
        Order existing = order(1L, 101L, 50.00, "PENDING");
        stubFindById(1L, existing);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.updateOrderStatus(101L, 1L, "CONFIRMED");

        assertThat(capturedRole).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-RR-03: getOrdersByUser uses REPLICA role ──────────────────────────

    @Test
    @DisplayName("FVT-RR-03: getOrdersByUser routes to REPLICA (read operation)")
    void getOrdersByUser_routesToReplica() {
        stubFindByUserId(101L, List.of());
        orderService.getOrdersByUser(101L);
        assertThat(capturedRole).isEqualTo(Role.REPLICA);
    }

    // ── FVT-RR-04: getOrderById uses REPLICA role ─────────────────────────────

    @Test
    @DisplayName("FVT-RR-04: getOrderById routes to REPLICA (read operation)")
    void getOrderById_routesToReplica() {
        Order existing = order(1L, 101L, 75.00, "PENDING");
        stubFindById(1L, existing);

        orderService.getOrderById(101L, 1L);

        assertThat(capturedRole).isEqualTo(Role.REPLICA);
    }

    // ── FVT-RR-05: Role reverts to default PRIMARY after write ────────────────

    @Test
    @DisplayName("FVT-RR-05: Role is reset to default PRIMARY after a write operation completes")
    void role_resetToPrimary_afterWrite() {
        orderService.createOrder(101L, BigDecimal.TEN);
        // AOP clears the context — getRole() should return the default
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-RR-06: Role reverts to default PRIMARY after read ─────────────────

    @Test
    @DisplayName("FVT-RR-06: Role is reset to default PRIMARY after a read operation completes")
    void role_resetToPrimary_afterRead() {
        stubFindByUserId(101L, List.of());
        orderService.getOrdersByUser(101L);
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-RR-07: Alternating read/write operations each use correct role ────

    @Test
    @DisplayName("FVT-RR-07: Alternating write-read-write uses correct role each time")
    void alternatingOperations_useCorrectRoleEachTime() {
        stubFindByUserId(101L, List.of());
        Order existing = order(1L, 101L, 100.00, "PENDING");
        stubFindById(1L, existing);
        when(orderRepository.save(any())).thenAnswer(inv -> {
            capturedRole = ShardContextHolder.getRole();
            return inv.getArgument(0);
        });

        // Write 1 → PRIMARY
        orderService.createOrder(101L, BigDecimal.TEN);
        assertThat(capturedRole).isEqualTo(Role.PRIMARY);

        // Read → REPLICA
        stubFindByUserId(101L, List.of());
        orderService.getOrdersByUser(101L);
        assertThat(capturedRole).isEqualTo(Role.REPLICA);

        // Write 2 → PRIMARY
        orderService.updateOrderStatus(101L, 1L, "SHIPPED");
        assertThat(capturedRole).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-RR-08: Role and shard are set independently (not entangled) ───────

    @Test
    @DisplayName("FVT-RR-08: Role and shard index are set independently without interference")
    void roleAndShard_areSetIndependently() {
        // userId=2 → shard 2, role PRIMARY
        orderService.createOrder(2L, BigDecimal.TEN);
        assertThat(capturedShard).isEqualTo(2);
        assertThat(capturedRole).isEqualTo(Role.PRIMARY);

        resetMocks();
        stubSave();

        // userId=1 → shard 1, role PRIMARY
        orderService.createOrder(1L, BigDecimal.TEN);
        assertThat(capturedShard).isEqualTo(1);
        assertThat(capturedRole).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-RR-09: No role leaks between threads ──────────────────────────────

    @Test
    @DisplayName("FVT-RR-09: Role set on one thread is not visible on another thread")
    void role_doesNotLeakBetweenThreads() throws InterruptedException {
        ShardContextHolder.setRole(Role.REPLICA);

        Role[] otherThreadRole = {null};
        Thread t = new Thread(() -> otherThreadRole[0] = ShardContextHolder.getRole());
        t.start();
        t.join();

        // Other thread should see the default PRIMARY, not REPLICA
        assertThat(otherThreadRole[0]).isEqualTo(Role.PRIMARY);
        ShardContextHolder.clear();
    }

    // ── FVT-RR-10: Shard does not leak between threads ────────────────────────

    @Test
    @DisplayName("FVT-RR-10: Shard set on one thread is not visible on another thread")
    void shard_doesNotLeakBetweenThreads() throws InterruptedException {
        ShardContextHolder.setShard(2);

        Integer[] otherThreadShard = {-99};
        Thread t = new Thread(() -> otherThreadShard[0] = ShardContextHolder.getShard());
        t.start();
        t.join();

        assertThat(otherThreadShard[0]).isNull();
        ShardContextHolder.clear();
    }
}
