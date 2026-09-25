package com.example.sharding.functional;

import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.DataSourceKey;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional Verification Test — Shard Routing
 *
 * <p>Verifies that the routing infrastructure (ShardContextHolder +
 * DataSourceKey) correctly builds routing keys, that the shard index
 * propagates through the service, and that context is always cleaned
 * up after each operation.
 */
@DisplayName("FVT: Shard Routing")
public class ShardRoutingFVT extends FunctionalTestBase {

    @BeforeEach
    void setup() {
        resetMocks();
        stubSave();
    }

    // ── FVT-SR-01: DataSourceKey captures correct shard + role combination ────

    @Test
    @DisplayName("FVT-SR-01: DataSourceKey built from context holds correct shard index and role")
    void dataSourceKey_capturesCorrectShardAndRole() {
        ShardContextHolder.setShard(2);
        ShardContextHolder.setRole(Role.PRIMARY);

        // Simulate what ShardReplicaRoutingDataSource.determineCurrentLookupKey() does
        int   shard = ShardContextHolder.getShard() != null ? ShardContextHolder.getShard() : 0;
        Role  role  = ShardContextHolder.getRole();
        DataSourceKey key = new DataSourceKey(shard, role);

        assertThat(key.getShardIndex()).isEqualTo(2);
        assertThat(key.getRole()).isEqualTo(Role.PRIMARY);

        ShardContextHolder.clear();
    }

    // ── FVT-SR-02: When context is empty, routing falls back to shard 0 ───────

    @Test
    @DisplayName("FVT-SR-02: Routing key defaults to shard 0 / PRIMARY when no context is set")
    void routingKey_defaultsShard0_whenContextEmpty() {
        ShardContextHolder.clear();

        int   shard = ShardContextHolder.getShard() != null ? ShardContextHolder.getShard() : 0;
        Role  role  = ShardContextHolder.getRole();
        DataSourceKey key = new DataSourceKey(shard, role);

        assertThat(key.getShardIndex()).isEqualTo(0);
        assertThat(key.getRole()).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-SR-03: Shard index is set correctly per userId ────────────────────

    @ParameterizedTest(name = "FVT-SR-03: userId={0} routes to shard {1}")
    @CsvSource({
        "0,  0",
        "1,  1",
        "2,  2",
        "3,  0",
        "101, 2",
        "200, 2",
        "201, 0",
        "300, 0"
    })
    @DisplayName("FVT-SR-03: createOrder sets the correct shard index in context")
    void createOrder_setsCorrectShardInContext(long userId, int expectedShard) {
        orderService.createOrder(userId, BigDecimal.TEN);
        assertThat(capturedShard).isEqualTo(expectedShard);
    }

    // ── FVT-SR-04: Context cleared after each write ───────────────────────────

    @Test
    @DisplayName("FVT-SR-04: ShardContextHolder is fully cleared after createOrder completes")
    void contextCleared_afterCreateOrder() {
        orderService.createOrder(101L, BigDecimal.TEN);

        // AOP clears context after the transactional method returns
        assertThat(ShardContextHolder.getShard()).isNull();
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY); // default
    }

    // ── FVT-SR-05: Context cleared after each read ────────────────────────────

    @Test
    @DisplayName("FVT-SR-05: ShardContextHolder is fully cleared after getOrdersByUser completes")
    void contextCleared_afterGetOrdersByUser() {
        stubFindByUserId(101L, List.of());
        orderService.getOrdersByUser(101L);

        assertThat(ShardContextHolder.getShard()).isNull();
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    // ── FVT-SR-06: Sequential calls on different shards don't leak context ────

    @Test
    @DisplayName("FVT-SR-06: Sequential calls across different shards have independent context")
    void sequentialCalls_doNotLeakContext() {
        // userId=0 → shard 0, userId=1 → shard 1, userId=2 → shard 2
        long[] users = {0L, 1L, 2L};
        int[] expectedShards = {0, 1, 2};

        for (int i = 0; i < users.length; i++) {
            orderService.createOrder(users[i], BigDecimal.TEN);
            assertThat(capturedShard)
                .as("After call for userId=%d", users[i])
                .isEqualTo(expectedShards[i]);
            // after each call context is clean
            assertThat(ShardContextHolder.getShard()).isNull();
        }
    }

    // ── FVT-SR-07: DataSourceKey equality is used for routing map lookup ──────

    @Test
    @DisplayName("FVT-SR-07: DataSourceKey with same shard+role resolves to equal key for map lookup")
    void dataSourceKey_usedCorrectlyForMapLookup() {
        DataSourceKey k1 = new DataSourceKey(1, Role.REPLICA);
        DataSourceKey k2 = new DataSourceKey(1, Role.REPLICA);

        // Java HashMap requires equals+hashCode consistency
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());

        java.util.Map<DataSourceKey, String> map = new java.util.HashMap<>();
        map.put(k1, "ds-shard1-replica");
        assertThat(map.get(k2)).isEqualTo("ds-shard1-replica");
    }

    // ── FVT-SR-08: All 6 routing keys (3 shards × 2 roles) are distinct ──────

    @Test
    @DisplayName("FVT-SR-08: All 6 DataSource routing keys (3 shards × PRIMARY/REPLICA) are unique")
    void allSixRoutingKeys_areUnique() {
        java.util.Set<DataSourceKey> keys = new java.util.HashSet<>();
        for (int s = 0; s < DataSourceConfig.NUM_SHARDS; s++) {
            keys.add(new DataSourceKey(s, Role.PRIMARY));
            keys.add(new DataSourceKey(s, Role.REPLICA));
        }
        assertThat(keys).hasSize(DataSourceConfig.NUM_SHARDS * 2); // 6
    }

    // ── FVT-SR-09: Shard resolution is stable for large userIds ──────────────

    @Test
    @DisplayName("FVT-SR-09: Shard resolution is stable and in-range for extreme userId values")
    void shardResolution_stableForExtremeValues() {
        long[] extremes = {Long.MAX_VALUE, Long.MIN_VALUE + 1, 0L, -1L, 1_000_000_000L};
        for (long uid : extremes) {
            int shard = DataSourceConfig.resolveShardIndex(uid);
            assertThat(shard)
                .as("shard for userId=%d", uid)
                .isBetween(0, DataSourceConfig.NUM_SHARDS - 1);
        }
    }

    // ── FVT-SR-10: toString of DataSourceKey is human-readable ───────────────

    @Test
    @DisplayName("FVT-SR-10: DataSourceKey.toString is descriptive and contains shard + role")
    void dataSourceKey_toString_isDescriptive() {
        DataSourceKey key = new DataSourceKey(2, Role.REPLICA);
        String str = key.toString();
        assertThat(str).contains("2").contains("replica");
    }
}
