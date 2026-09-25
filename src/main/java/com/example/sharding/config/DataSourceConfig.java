package com.example.sharding.config;

import com.example.sharding.context.DataSourceKey;
import com.example.sharding.context.ShardContextHolder.Role;
import com.example.sharding.routing.ShardReplicaRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers all shard DataSources (primary + replica per shard) and wires them
 * into the {@link ShardReplicaRoutingDataSource}.
 *
 * <p>Topology (3 shards × 2 nodes each = 6 DataSources):
 * <pre>
 *   Shard 0 → Primary: localhost:5432/shard0_db
 *             Replica: localhost:5435/shard0_db
 *   Shard 1 → Primary: localhost:5433/shard1_db
 *             Replica: localhost:5437/shard1_db
 *   Shard 2 → Primary: localhost:5434/shard2_db
 *             Replica: localhost:5438/shard2_db
 * </pre>
 */
@Configuration
public class DataSourceConfig {

    public static final int NUM_SHARDS = 3;

    @Value("${datasource.username:app_user}")
    private String username;

    @Value("${datasource.password:secret}")
    private String password;

    @Value("${datasource.pool.max-size:10}")
    private int maxPoolSize;

    @Bean
    @Primary
    public DataSource dataSource() {
        ShardReplicaRoutingDataSource routing = new ShardReplicaRoutingDataSource();

        Map<Object, Object> dataSources = new HashMap<>();

        // ── Shard 0 ────────────────────────────────────────────────
        dataSources.put(
                new DataSourceKey(0, Role.PRIMARY),
                buildDataSource("localhost", 5432, "shard0_db", "shard0-primary"));
        dataSources.put(
                new DataSourceKey(0, Role.REPLICA),
                buildDataSource("localhost", 5435, "shard0_db", "shard0-replica"));

        // ── Shard 1 ────────────────────────────────────────────────
        dataSources.put(
                new DataSourceKey(1, Role.PRIMARY),
                buildDataSource("localhost", 5433, "shard1_db", "shard1-primary"));
        dataSources.put(
                new DataSourceKey(1, Role.REPLICA),
                buildDataSource("localhost", 5437, "shard1_db", "shard1-replica"));

        // ── Shard 2 ────────────────────────────────────────────────
        dataSources.put(
                new DataSourceKey(2, Role.PRIMARY),
                buildDataSource("localhost", 5434, "shard2_db", "shard2-primary"));
        dataSources.put(
                new DataSourceKey(2, Role.REPLICA),
                buildDataSource("localhost", 5438, "shard2_db", "shard2-replica"));

        routing.setTargetDataSources(dataSources);
        // Default fallback → shard 0 primary
        routing.setDefaultTargetDataSource(
                dataSources.get(new DataSourceKey(0, Role.PRIMARY)));

        return routing;
    }

    // ── Shard resolution ──────────────────────────────────────────────────────

    /**
     * Hash-based shard resolution: distributes user IDs evenly across shards.
     *
     * @param userId the shard key
     * @return shard index in range [0, NUM_SHARDS)
     */
    public static int resolveShardIndex(long userId) {
        return (int) (Math.abs(userId) % NUM_SHARDS);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private DataSource buildDataSource(String host, int port, String db, String poolName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(poolName);
        ds.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, db));
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(maxPoolSize);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30_000);
        ds.setIdleTimeout(600_000);
        ds.setMaxLifetime(1_800_000);
        return ds;
    }
}
