Feature: Replication Routing — Read/Write Splitting
  As the replication routing system
  I want to direct write operations to the PRIMARY and reads to the REPLICA
  So that the primary handles all mutations and replicas handle read traffic

  # ── Write → PRIMARY ──────────────────────────────────────────────────────────

  Scenario: Creating an order routes to PRIMARY
    When I create an order for user 101 with amount 250.00
    Then the routing role was PRIMARY

  Scenario: Updating order status routes to PRIMARY
    Given an order exists for user 101 with amount 100.00
    When I update the order status to "SHIPPED" for user 101
    Then the routing role was PRIMARY

  # ── Read → REPLICA ───────────────────────────────────────────────────────────

  Scenario: Getting orders by user routes to REPLICA
    When I request all orders for user 101
    Then the routing role was REPLICA

  Scenario: Getting a single order routes to REPLICA
    Given an order exists for user 101 with amount 75.00
    When I request the order by its ID for user 101
    Then the routing role was REPLICA

  # ── Role isolation ────────────────────────────────────────────────────────────

  Scenario: Role is reset to PRIMARY after a read operation
    When I request all orders for user 101
    And I check the current routing role
    Then the routing role is the default PRIMARY

  Scenario: Role is reset to PRIMARY after a write operation
    When I create an order for user 101 with amount 10.00
    And I check the current routing role
    Then the routing role is the default PRIMARY

  Scenario: Consecutive read and write operations use independent roles
    When I create an order for user 101 with amount 100.00
    Then the routing role was PRIMARY
    When I request all orders for user 101
    Then the routing role was REPLICA
    When I update the order status to "CONFIRMED" for user 101
    Then the routing role was PRIMARY

  # ── Role and shard independence ───────────────────────────────────────────────

  Scenario: Role and shard index are set independently
    When I create an order for user 101 with amount 50.00
    Then the shard context was set to shard 2
    And the routing role was PRIMARY

  Scenario: Read role and shard are set independently
    When I request all orders for user 4
    Then the shard context was set to shard 1
    And the routing role was REPLICA
