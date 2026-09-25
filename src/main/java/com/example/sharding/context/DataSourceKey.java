package com.example.sharding.context;

import com.example.sharding.context.ShardContextHolder.Role;

import java.util.Objects;

/**
 * Composite key used to look up the correct DataSource from the routing map.
 * Combines the shard index (which physical database cluster) and the role
 * (PRIMARY for writes, REPLICA for reads).
 */
public class DataSourceKey {

    private final int  shardIndex;
    private final Role role;

    public DataSourceKey(int shardIndex, Role role) {
        this.shardIndex = shardIndex;
        this.role       = role;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataSourceKey)) return false;
        DataSourceKey that = (DataSourceKey) o;
        return shardIndex == that.shardIndex && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardIndex, role);
    }

    @Override
    public String toString() {
        return "shard_" + shardIndex + "_" + role.name().toLowerCase();
    }
}
