package com.example.sharding.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DataSourceConfig#resolveShardIndex(long)}.
 * No Spring context is needed — pure logic tests.
 */
@DisplayName("DataSourceConfig — shard resolution")
class DataSourceConfigTest {

    private static final int NUM_SHARDS = DataSourceConfig.NUM_SHARDS; // 3

    // ── Basic hash distribution ───────────────────────────────────────────────

    @ParameterizedTest(name = "userId={0} → shard={1}")
    @CsvSource({
        "0,  0",
        "1,  1",
        "2,  2",
        "3,  0",
        "4,  1",
        "5,  2",
        "100, 1",
        "101, 2",
        "102, 0",
        "999, 0",
    })
    @DisplayName("resolveShardIndex distributes userId correctly (userId % 3)")
    void resolveShardIndex_correctDistribution(long userId, int expectedShard) {
        assertThat(DataSourceConfig.resolveShardIndex(userId)).isEqualTo(expectedShard);
    }

    @Test
    @DisplayName("Result is always in range [0, NUM_SHARDS)")
    void resolveShardIndex_alwaysInRange() {
        for (long userId = 0; userId < 1000; userId++) {
            int shard = DataSourceConfig.resolveShardIndex(userId);
            assertThat(shard)
                    .as("shard for userId=%d", userId)
                    .isBetween(0, NUM_SHARDS - 1);
        }
    }

    @Test
    @DisplayName("Negative userId is handled safely (Math.abs)")
    void resolveShardIndex_negativeUserId() {
        long negativeId = -101L;
        int shard = DataSourceConfig.resolveShardIndex(negativeId);
        assertThat(shard).isBetween(0, NUM_SHARDS - 1);
        // -101 → abs = 101 → 101 % 3 = 2
        assertThat(shard).isEqualTo(2);
    }

    @Test
    @DisplayName("Same userId always resolves to the same shard (deterministic)")
    void resolveShardIndex_isDeterministic() {
        long userId = 12345L;
        int first  = DataSourceConfig.resolveShardIndex(userId);
        int second = DataSourceConfig.resolveShardIndex(userId);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("Distribution across 1000 users is roughly even (±10%)")
    void resolveShardIndex_evenDistribution() {
        int[] counts = new int[NUM_SHARDS];
        int total = 999;
        for (long userId = 1; userId <= total; userId++) {
            counts[DataSourceConfig.resolveShardIndex(userId)]++;
        }
        int expected = total / NUM_SHARDS;
        int tolerance = (int) (expected * 0.10);
        for (int i = 0; i < NUM_SHARDS; i++) {
            assertThat(counts[i])
                    .as("shard %d count", i)
                    .isBetween(expected - tolerance, expected + tolerance);
        }
    }

    @Test
    @DisplayName("Long.MAX_VALUE is handled without overflow and result is in valid range")
    void resolveShardIndex_longMaxValue_inRange() {
        int shard = DataSourceConfig.resolveShardIndex(Long.MAX_VALUE);
        assertThat(shard).isBetween(0, NUM_SHARDS - 1);
    }
}
