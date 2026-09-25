package com.example.sharding.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.sharding.context.ShardContextHolder.Role.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShardContextHolder}.
 * Verifies ThreadLocal isolation, default values, and cleanup.
 */
@DisplayName("ShardContextHolder")
class ShardContextHolderTest {

    @AfterEach
    void cleanup() {
        ShardContextHolder.clear();
    }

    // ── setShard / getShard ───────────────────────────────────────────────────

    @Test
    @DisplayName("getShard returns null before any shard is set")
    void getShard_returnsNullInitially() {
        assertThat(ShardContextHolder.getShard()).isNull();
    }

    @Test
    @DisplayName("setShard stores the shard index and getShard returns it")
    void setShard_andGetShard() {
        ShardContextHolder.setShard(2);
        assertThat(ShardContextHolder.getShard()).isEqualTo(2);
    }

    @Test
    @DisplayName("setShard can be overwritten")
    void setShard_canBeOverwritten() {
        ShardContextHolder.setShard(0);
        ShardContextHolder.setShard(1);
        assertThat(ShardContextHolder.getShard()).isEqualTo(1);
    }

    // ── setRole / getRole ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getRole defaults to PRIMARY when nothing is set")
    void getRole_defaultsToPrimary() {
        assertThat(ShardContextHolder.getRole()).isEqualTo(PRIMARY);
    }

    @Test
    @DisplayName("setRole(REPLICA) is reflected by getRole")
    void setRole_replica() {
        ShardContextHolder.setRole(REPLICA);
        assertThat(ShardContextHolder.getRole()).isEqualTo(REPLICA);
    }

    @Test
    @DisplayName("setRole(PRIMARY) is reflected by getRole")
    void setRole_primary() {
        ShardContextHolder.setRole(PRIMARY);
        assertThat(ShardContextHolder.getRole()).isEqualTo(PRIMARY);
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("clear() resets both shard and role to their defaults")
    void clear_resetsBothValues() {
        ShardContextHolder.setShard(1);
        ShardContextHolder.setRole(REPLICA);

        ShardContextHolder.clear();

        assertThat(ShardContextHolder.getShard()).isNull();
        assertThat(ShardContextHolder.getRole()).isEqualTo(PRIMARY); // default
    }

    // ── ThreadLocal isolation ─────────────────────────────────────────────────

    @Test
    @DisplayName("Values set on one thread are not visible on another thread")
    void threadLocal_isolation() throws InterruptedException {
        ShardContextHolder.setShard(2);
        ShardContextHolder.setRole(REPLICA);

        Integer[] shardFromOtherThread = {null};
        Thread other = new Thread(() -> shardFromOtherThread[0] = ShardContextHolder.getShard());
        other.start();
        other.join();

        assertThat(shardFromOtherThread[0])
                .as("Other thread must see null, not the value set on this thread")
                .isNull();
    }
}
