package com.example.sharding.behaviour.steps;

import com.example.sharding.behaviour.CucumberSpringContext;
import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import io.cucumber.java.en.*;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for replication-routing.feature.
 * Verifies that write operations route to PRIMARY and reads route to REPLICA.
 */
public class ReplicationRoutingSteps {

    private final CucumberSpringContext ctx;
    private Role checkedRole;

    @Autowired
    public ReplicationRoutingSteps(CucumberSpringContext ctx) {
        this.ctx = ctx;
    }

    // ── When ──────────────────────────────────────────────────────────────────

    @When("I check the current routing role")
    public void iCheckTheCurrentRoutingRole() {
        // After the service call the AOP clears the context,
        // so we check the default — this validates that cleanup happened
        checkedRole = ShardContextHolder.getRole();
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Then("the routing role was PRIMARY")
    public void theRoutingRoleWasPrimary() {
        assertThat(ctx.capturedRole)
                .as("Expected captured role PRIMARY but was %s", ctx.capturedRole)
                .isEqualTo(Role.PRIMARY);
    }

    @Then("the routing role was REPLICA")
    public void theRoutingRoleWasReplica() {
        assertThat(ctx.capturedRole)
                .as("Expected captured role REPLICA but was %s", ctx.capturedRole)
                .isEqualTo(Role.REPLICA);
    }

    @Then("the routing role is the default PRIMARY")
    public void theRoutingRoleIsTheDefaultPrimary() {
        // After AOP clears the context, getRole() defaults to PRIMARY
        assertThat(checkedRole).isEqualTo(Role.PRIMARY);
    }
}
