package com.example.sharding.context;

import com.example.sharding.context.ShardContextHolder.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.sharding.context.ShardContextHolder.Role.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DataSourceKey} — equality, hashCode, and toString.
 */
@DisplayName("DataSourceKey")
public class DataSourceKeyTest {

    @Test
    @DisplayName("Two keys with the same shard and role are equal")
    void equals_sameShardAndRole() {
        DataSourceKey a = new DataSourceKey(1, PRIMARY);
        DataSourceKey b = new DataSourceKey(1, PRIMARY);
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Two keys with different shard index are not equal")
    void equals_differentShard() {
        DataSourceKey a = new DataSourceKey(0, PRIMARY);
        DataSourceKey b = new DataSourceKey(1, PRIMARY);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Two keys with different role are not equal")
    void equals_differentRole() {
        DataSourceKey a = new DataSourceKey(1, PRIMARY);
        DataSourceKey b = new DataSourceKey(1, REPLICA);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Equal keys produce the same hashCode")
    void hashCode_equalKeys() {
        DataSourceKey a = new DataSourceKey(2, REPLICA);
        DataSourceKey b = new DataSourceKey(2, REPLICA);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("toString contains shard index and role name")
    void toString_containsShardAndRole() {
        DataSourceKey key = new DataSourceKey(1, REPLICA);
        assertThat(key.toString())
                .contains("1")
                .contains("replica");
    }

    @Test
    @DisplayName("Key is not equal to null")
    void equals_notNull() {
        DataSourceKey key = new DataSourceKey(0, PRIMARY);
        assertThat(key).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Key is equal to itself (reflexive)")
    void equals_reflexive() {
        DataSourceKey key = new DataSourceKey(0, PRIMARY);
        assertThat(key).isEqualTo(key);
    }

    @Test
    @DisplayName("All shard/role combinations produce unique keys")
    void allCombinations_areUnique() {
        Role[] roles = {PRIMARY, REPLICA};
        int shards = 3;
        java.util.Set<DataSourceKey> keys = new java.util.HashSet<>();
        for (int s = 0; s < shards; s++) {
            for (Role r : roles) {
                keys.add(new DataSourceKey(s, r));
            }
        }
        assertThat(keys).hasSize(shards * roles.length);
    }
}
