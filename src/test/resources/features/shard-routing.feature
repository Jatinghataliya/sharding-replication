Feature: Shard Routing
  As the sharding system
  I want to route every request to the correct shard
  So that data is stored and retrieved from the right database node

  # ── Shard Resolution ─────────────────────────────────────────────────────────

  Scenario Outline: User ID is routed to the correct shard
    When I resolve the shard for user ID <userId>
    Then the shard index should be <expectedShard>

    Examples:
      | userId | expectedShard |
      | 0      | 0             |
      | 1      | 1             |
      | 2      | 2             |
      | 3      | 0             |
      | 4      | 1             |
      | 5      | 2             |
      | 99     | 0             |
      | 100    | 1             |
      | 101    | 2             |
      | 300    | 0             |

  Scenario: Shard resolution is deterministic for the same user ID
    When I resolve the shard for user ID 101 three times
    Then all resolutions return the same shard index

  Scenario: Negative user IDs are handled safely
    When I resolve the shard for user ID -101
    Then the shard index should be 2
    And the shard index is within the valid range

  Scenario: Shard index is always in range 0 to 2
    When I resolve shards for 100 sequential user IDs starting from 1
    Then every shard index is within the valid range

  Scenario: Shard distribution is even across 300 requests
    When I resolve shards for 300 sequential user IDs starting from 0
    Then each shard receives exactly 100 requests

  # ── Write routing to Primary ─────────────────────────────────────────────────

  Scenario: Creating an order sets shard context on the correct shard
    When I create an order for user 101 with amount 50.00
    Then the shard context was set to shard 2

  Scenario: Updating an order sets shard context on the correct shard
    Given an order exists for user 3 with amount 10.00
    When I update the order status to "CONFIRMED" for user 3
    Then the shard context was set to shard 0

  # ── Read routing to Replica ───────────────────────────────────────────────────

  Scenario: Reading orders for user routes to correct shard
    When I request all orders for user 4
    Then the shard context was set to shard 1

  Scenario: Users on different shards route independently
    When I create an order for user 0 with amount 10.00
    Then the shard context was set to shard 0
    When I create an order for user 1 with amount 20.00
    Then the shard context was set to shard 1
    When I create an order for user 2 with amount 30.00
    Then the shard context was set to shard 2
