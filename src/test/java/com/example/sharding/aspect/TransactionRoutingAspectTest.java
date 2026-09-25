package com.example.sharding.aspect;

import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for shard routing role logic used by {@link TransactionRoutingAspect}.
 *
 * <p>The AOP mechanism itself is exercised end-to-end in {@code OrderControllerTest}.
 * Here we verify the pure role-detection logic in isolation.</p>
 */
@DisplayName("TransactionRoutingAspect — role logic")
public class TransactionRoutingAspectTest {

    @AfterEach
    void cleanup() {
        ShardContextHolder.clear();
    }

    @Test
    @DisplayName("Setting REPLICA role is reflected in context")
    void replicaRole_isSetCorrectly() {
        ShardContextHolder.setRole(Role.REPLICA);
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.REPLICA);
    }

    @Test
    @DisplayName("Setting PRIMARY role is reflected in context")
    void primaryRole_isSetCorrectly() {
        ShardContextHolder.setRole(Role.PRIMARY);
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    @Test
    @DisplayName("Role defaults to PRIMARY if not explicitly set")
    void role_defaultsToPrimary() {
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    @Test
    @DisplayName("Overwriting REPLICA with PRIMARY changes the role")
    void overwrite_replicaWithPrimary() {
        ShardContextHolder.setRole(Role.REPLICA);
        ShardContextHolder.setRole(Role.PRIMARY);
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    @Test
    @DisplayName("clear() resets role to default (PRIMARY)")
    void clear_resetsRoleToDefault() {
        ShardContextHolder.setRole(Role.REPLICA);
        ShardContextHolder.clear();
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.PRIMARY);
    }

    @Test
    @DisplayName("Role is independent of shard index")
    void role_isIndependentOfShard() {
        ShardContextHolder.setShard(2);
        ShardContextHolder.setRole(Role.REPLICA);

        assertThat(ShardContextHolder.getShard()).isEqualTo(2);
        assertThat(ShardContextHolder.getRole()).isEqualTo(Role.REPLICA);
    }
}
