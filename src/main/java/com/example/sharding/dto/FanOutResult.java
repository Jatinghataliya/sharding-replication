package com.example.sharding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "Aggregated cross-shard fan-out query result")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class FanOutResult {

    @Schema(description = "Total number of orders across all responding shards", example = "42")
    private int totalOrders;

    @Schema(description = "Per-shard detailed query results")
    private List<ShardQueryResult> resultsPerShard = new ArrayList<>();

    @Schema(description = "Total wall-clock fan-out query time in milliseconds", example = "35")
    private long totalQueryTimeMs;

    @Schema(description = "Number of shards queried", example = "3")
    private int shardsQueried;

    @Schema(description = "True if one or more shards failed or timed out", example = "false")
    private boolean degraded;

    public FanOutResult(int totalOrders, List<ShardQueryResult> resultsPerShard, long totalQueryTimeMs, int shardsQueried, boolean degraded) {
        this.totalOrders = totalOrders;
        this.resultsPerShard = resultsPerShard != null ? resultsPerShard : new ArrayList<>();
        this.totalQueryTimeMs = totalQueryTimeMs;
        this.shardsQueried = shardsQueried;
        this.degraded = degraded;
    }
}
