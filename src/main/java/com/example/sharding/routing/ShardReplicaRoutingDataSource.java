package com.example.sharding.routing;

import com.example.sharding.context.DataSourceKey;
import com.example.sharding.context.ShardContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Custom {@link AbstractRoutingDataSource} that selects the correct DataSource
 * based on the current shard index and read/write role stored in
 * {@link ShardContextHolder}.
 *
 * <p>The routing key is a {@link DataSourceKey} — a combination of
 * (shardIndex, Role). Spring resolves this key against the target DataSource map
 * registered in {@link com.example.sharding.config.DataSourceConfig}.</p>
 */
public class ShardReplicaRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(ShardReplicaRoutingDataSource.class);

    @Override
    protected Object determineCurrentLookupKey() {
        int shardIndex = ShardContextHolder.getShard() != null
                ? ShardContextHolder.getShard()
                : 0;

        ShardContextHolder.Role role = ShardContextHolder.getRole();

        DataSourceKey key = new DataSourceKey(shardIndex, role);
        log.debug("Routing to DataSource → {}", key);
        return key;
    }
}
