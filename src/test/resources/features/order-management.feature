Feature: Order Management
  As a user of the sharding-replication system
  I want to create, read and update orders
  So that orders are persisted and retrieved correctly

  Background:
    Given the order service is available

  # ── Create Order ─────────────────────────────────────────────────────────────

  Scenario: Successfully create an order
    When I create an order for user 101 with amount 250.00
    Then the order is created successfully
    And the order has status "PENDING"
    And the order belongs to user 101
    And the order amount is 250.00

  Scenario: Creating an order assigns PENDING status by default
    When I create an order for user 202 with amount 99.99
    Then the order has status "PENDING"

  Scenario: Creating multiple orders for the same user
    When I create an order for user 101 with amount 100.00
    And I create another order for user 101 with amount 200.00
    Then both orders are created successfully

  Scenario: Creating orders for different users
    When I create an order for user 101 with amount 50.00
    And I create an order for user 202 with amount 75.00
    Then all orders are created successfully

  # ── Retrieve Orders ───────────────────────────────────────────────────────────

  Scenario: Retrieve orders for a user who has orders
    Given an order exists for user 101 with amount 250.00
    When I request all orders for user 101
    Then I receive a list containing 1 order
    And each order belongs to user 101

  Scenario: Retrieve orders for a user who has no orders
    When I request all orders for user 999
    Then I receive an empty list

  Scenario: Retrieve a specific order by ID
    Given an order exists for user 101 with amount 150.00
    When I request the order by its ID for user 101
    Then I receive the correct order
    And the order amount is 150.00

  Scenario: Retrieve a non-existent order throws an error
    When I request order with ID 99999 for user 101
    Then an error is thrown with message containing "Order not found"

  # ── Update Order Status ───────────────────────────────────────────────────────

  Scenario: Update order status from PENDING to CONFIRMED
    Given an order exists for user 101 with amount 100.00
    When I update the order status to "CONFIRMED" for user 101
    Then the order has status "CONFIRMED"

  Scenario: Update order status through the full lifecycle
    Given an order exists for user 101 with amount 100.00
    When I update the order status to "CONFIRMED" for user 101
    And I update the order status to "SHIPPED" for user 101
    And I update the order status to "DELIVERED" for user 101
    Then the order has status "DELIVERED"

  Scenario: Update status of a non-existent order throws an error
    When I update order with ID 99999 to status "SHIPPED" for user 101
    Then an error is thrown with message containing "Order not found"
