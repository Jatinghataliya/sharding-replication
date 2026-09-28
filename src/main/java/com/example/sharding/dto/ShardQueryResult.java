package com.example.sharding.dto;

import com.example.sharding.entity.Order;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "Query result from a single shard")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ShardQueryResult {

    @Schema(description = "Shard index (0, 1, 2)", example = "0")
    private int shardIndex;

    @Schema(description = "List of orders returned by this shard")
    private List<Order> orders = new ArrayList<>();

    @Schema(description = "Execution time on this shard in milliseconds", example = "12")
    private long queryTimeMs;

    @Schema(description = "Indicates whether the query on this shard succeeded", example = "true")
    private boolean success = true;

    @Schema(description = "Error message if the shard query failed", example = "Shard connection timeout")
    private String errorMessage;

    public ShardQueryResult(int shardIndex, List<Order> orders, long queryTimeMs) {
        this.shardIndex = shardIndex;
        this.orders = orders != null ? orders : new ArrayList<>();
        this.queryTimeMs = queryTimeMs;
        this.success = true;
        this.errorMessage = null;
    }

    public static ShardQueryResult failure(int shardIndex, long queryTimeMs, String errorMessage) {
        ShardQueryResult result = new ShardQueryResult();
        result.setShardIndex(shardIndex);
        result.setOrders(new ArrayList<>());
        result.setQueryTimeMs(queryTimeMs);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
