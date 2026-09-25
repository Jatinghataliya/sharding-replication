package com.example.sharding.behaviour.steps;

import com.example.sharding.behaviour.CucumberSpringContext;
import com.example.sharding.config.DataSourceConfig;
import com.example.sharding.context.ShardContextHolder;
import io.cucumber.java.en.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for shard-routing.feature.
 * Covers shard resolution correctness, distribution, and context propagation.
 */
public class ShardRoutingSteps {

    private final CucumberSpringContext ctx;

    // reused across scenario steps
    private int resolvedShard = -1;
    private final List<Integer> resolvedShards = new ArrayList<>();

    @Autowired
    public ShardRoutingSteps(CucumberSpringContext ctx) {
        this.ctx = ctx;
    }

    // ── Shard resolution ─────────────────────────────────────────────────────

    @When("I resolve the shard for user ID {long}")
    public void iResolveTheShardForUserId(long userId) {
        resolvedShard = DataSourceConfig.resolveShardIndex(userId);
    }

    @When("I resolve the shard for user ID {long} three times")
    public void iResolveTheShardForUserIdThreeTimes(long userId) {
        resolvedShards.clear();
        for (int i = 0; i < 3; i++) {
            resolvedShards.add(DataSourceConfig.resolveShardIndex(userId));
        }
    }

    @When("I resolve shards for {int} sequential user IDs starting from {int}")
    public void iResolveShardForSequentialUserIds(int count, int startFrom) {
        resolvedShards.clear();
        for (int i = startFrom; i < startFrom + count; i++) {
            resolvedShards.add(DataSourceConfig.resolveShardIndex(i));
        }
    }

    // ── Then — shard resolution ───────────────────────────────────────────────

    @Then("the shard index should be {int}")
    public void theShardIndexShouldBe(int expectedShard) {
        assertThat(resolvedShard).isEqualTo(expectedShard);
    }

    @Then("all resolutions return the same shard index")
    public void allResolutionsReturnTheSameShardIndex() {
        assertThat(resolvedShards).isNotEmpty();
        int first = resolvedShards.get(0);
        resolvedShards.forEach(s -> assertThat(s).isEqualTo(first));
    }

    @Then("the shard index is within the valid range")
    public void theShardIndexIsWithinTheValidRange() {
        int shard = resolvedShard != -1 ? resolvedShard : resolvedShards.get(resolvedShards.size() - 1);
        assertThat(shard).isBetween(0, DataSourceConfig.NUM_SHARDS - 1);
    }

    @Then("every shard index is within the valid range")
    public void everyShardIndexIsWithinTheValidRange() {
        assertThat(resolvedShards).isNotEmpty();
        resolvedShards.forEach(s ->
                assertThat(s).isBetween(0, DataSourceConfig.NUM_SHARDS - 1));
    }

    @Then("each shard receives exactly {int} requests")
    public void eachShardReceivesExactlyRequests(int expectedCount) {
        int[] counts = new int[DataSourceConfig.NUM_SHARDS];
        resolvedShards.forEach(s -> counts[s]++);
        for (int i = 0; i < DataSourceConfig.NUM_SHARDS; i++) {
            assertThat(counts[i])
                    .as("Shard %d should receive %d requests", i, expectedCount)
                    .isEqualTo(expectedCount);
        }
    }

    // ── Then — context propagation ────────────────────────────────────────────

    @Then("the shard context was set to shard {int}")
    public void theShardContextWasSetToShard(int expectedShard) {
        assertThat(ctx.capturedShard)
                .as("Expected shard %d but captured %d", expectedShard, ctx.capturedShard)
                .isEqualTo(expectedShard);
    }
}
