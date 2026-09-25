package com.example.sharding.behaviour.steps;

import com.example.sharding.behaviour.CucumberSpringContext;
import com.example.sharding.entity.Order;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Step definitions for order-management.feature.
 * Covers create, read, and update order scenarios.
 */
public class OrderSteps {

    private final CucumberSpringContext ctx;

    @Autowired
    public OrderSteps(CucumberSpringContext ctx) {
        this.ctx = ctx;
    }

    @Before
    public void beforeEach() {
        ctx.reset();
        ctx.stubSaveCapturingContext();
    }

    @After
    public void afterEach() {
        // nothing — Spring resets mock state between scenarios
    }

    // ── Background ────────────────────────────────────────────────────────────

    @Given("the order service is available")
    public void theOrderServiceIsAvailable() {
        assertThat(ctx.orderService).isNotNull();
    }

    // ── Given steps ───────────────────────────────────────────────────────────

    @Given("an order exists for user {long} with amount {double}")
    public void anOrderExistsForUserWithAmount(long userId, double amount) {
        Order existing = CucumberSpringContext.buildOrder(1L, userId, amount, "PENDING");
        ctx.stubSaveCapturingContext();
        ctx.stubFindByIdCapturingContext(existing.getOrderId(), existing);
        ctx.stubFindByUserIdCapturingContext(userId, List.of(existing));
        // persist it through the service so ctx.lastCreatedOrder is set
        ctx.lastCreatedOrder = ctx.orderService.createOrder(userId, BigDecimal.valueOf(amount));
    }

    // ── When steps ────────────────────────────────────────────────────────────

    @When("I create an order for user {long} with amount {double}")
    public void iCreateAnOrderForUserWithAmount(long userId, double amount) {
        ctx.thrownException = null;
        try {
            ctx.lastCreatedOrder = ctx.orderService.createOrder(userId, BigDecimal.valueOf(amount));
            ctx.allCreatedOrders.add(ctx.lastCreatedOrder);
        } catch (Exception e) {
            ctx.thrownException = e;
        }
    }

    @When("I create another order for user {long} with amount {double}")
    public void iCreateAnotherOrderForUserWithAmount(long userId, double amount) {
        iCreateAnOrderForUserWithAmount(userId, amount);
    }

    @When("I request all orders for user {long}")
    public void iRequestAllOrdersForUser(long userId) {
        ctx.thrownException = null;
        // Only stub if the Given step hasn't already pre-loaded data for this userId
        if (!ctx.findByUserIdAlreadyStubbed) {
            ctx.stubFindByUserIdCapturingContext(userId, new ArrayList<>());
        }
        try {
            ctx.lastFetchedList = ctx.orderService.getOrdersByUser(userId);
        } catch (Exception e) {
            ctx.thrownException = e;
        }
    }

    @When("I request the order by its ID for user {long}")
    public void iRequestTheOrderByItsIdForUser(long userId) {
        ctx.thrownException = null;
        assertThat(ctx.lastCreatedOrder).isNotNull();
        long orderId = ctx.lastCreatedOrder.getOrderId();
        ctx.stubFindByIdCapturingContext(orderId, ctx.lastCreatedOrder);
        try {
            ctx.lastFetchedOrder = ctx.orderService.getOrderById(userId, orderId);
        } catch (Exception e) {
            ctx.thrownException = e;
        }
    }

    @When("I request order with ID {long} for user {long}")
    public void iRequestOrderWithIdForUser(long orderId, long userId) {
        ctx.thrownException = null;
        when(ctx.orderRepository.findById(orderId)).thenReturn(Optional.empty());
        try {
            ctx.lastFetchedOrder = ctx.orderService.getOrderById(userId, orderId);
        } catch (Exception e) {
            ctx.thrownException = e;
        }
    }

    @When("I update the order status to {string} for user {long}")
    public void iUpdateTheOrderStatusToForUser(String status, long userId) {
        ctx.thrownException = null;
        assertThat(ctx.lastCreatedOrder).isNotNull();
        long orderId = ctx.lastCreatedOrder.getOrderId();
        ctx.stubFindByIdCapturingContext(orderId, ctx.lastCreatedOrder);
        try {
            ctx.lastCreatedOrder = ctx.orderService.updateOrderStatus(userId, orderId, status);
        } catch (Exception e) {
            ctx.thrownException = e;
        }
    }

    @When("I update order with ID {long} to status {string} for user {long}")
    public void iUpdateOrderWithIdToStatusForUser(long orderId, String status, long userId) {
        ctx.thrownException = null;
        when(ctx.orderRepository.findById(orderId)).thenReturn(Optional.empty());
        try {
            ctx.orderService.updateOrderStatus(userId, orderId, status);
        } catch (Exception e) {
            ctx.thrownException = e;
        }
    }

    // ── Then steps ────────────────────────────────────────────────────────────

    @Then("the order is created successfully")
    public void theOrderIsCreatedSuccessfully() {
        assertThat(ctx.thrownException).isNull();
        assertThat(ctx.lastCreatedOrder).isNotNull();
        assertThat(ctx.lastCreatedOrder.getOrderId()).isNotNull();
    }

    @Then("both orders are created successfully")
    public void bothOrdersAreCreatedSuccessfully() {
        assertThat(ctx.thrownException).isNull();
        assertThat(ctx.allCreatedOrders).hasSize(2);
    }

    @Then("all orders are created successfully")
    public void allOrdersAreCreatedSuccessfully() {
        assertThat(ctx.thrownException).isNull();
        assertThat(ctx.allCreatedOrders).isNotEmpty();
    }

    @Then("the order has status {string}")
    public void theOrderHasStatus(String expectedStatus) {
        assertThat(ctx.lastCreatedOrder).isNotNull();
        assertThat(ctx.lastCreatedOrder.getStatus()).isEqualTo(expectedStatus);
    }

    @Then("the order belongs to user {long}")
    public void theOrderBelongsToUser(long userId) {
        assertThat(ctx.lastCreatedOrder.getUserId()).isEqualTo(userId);
    }

    @Then("the order amount is {double}")
    public void theOrderAmountIs(double amount) {
        Order order = ctx.lastFetchedOrder != null ? ctx.lastFetchedOrder : ctx.lastCreatedOrder;
        assertThat(order.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(amount));
    }

    @Then("I receive a list containing {int} order(s)")
    public void iReceiveAListContainingOrders(int count) {
        assertThat(ctx.lastFetchedList).hasSize(count);
    }

    @Then("I receive an empty list")
    public void iReceiveAnEmptyList() {
        assertThat(ctx.lastFetchedList).isEmpty();
    }

    @Then("each order belongs to user {long}")
    public void eachOrderBelongsToUser(long userId) {
        ctx.lastFetchedList.forEach(o ->
                assertThat(o.getUserId()).isEqualTo(userId));
    }

    @Then("I receive the correct order")
    public void iReceiveTheCorrectOrder() {
        assertThat(ctx.thrownException).isNull();
        assertThat(ctx.lastFetchedOrder).isNotNull();
    }

    @Then("an error is thrown with message containing {string}")
    public void anErrorIsThrownWithMessageContaining(String messageFragment) {
        assertThat(ctx.thrownException)
                .isNotNull()
                .hasMessageContaining(messageFragment);
    }
}
