package com.example.sharding.functional;

import com.example.sharding.controller.GlobalExceptionHandler;
import com.example.sharding.controller.OrderController;
import com.example.sharding.entity.Order;
import com.example.sharding.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional Verification Test — API Contract
 *
 * <p>Verifies the observable HTTP contract of every endpoint:
 * status codes, response body shape, Content-Type, error payload structure,
 * required parameters enforcement, and shard-info accuracy.
 *
 * <p>Uses {@code @WebMvcTest} — no real DB or datasource.
 */
@WebMvcTest(controllers = {OrderController.class, GlobalExceptionHandler.class})
@DisplayName("FVT: API Contract")
public class ApiContractFVT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  OrderService orderService;

    // ── FVT-AC-01: POST /api/orders → 201 Created with full order body ────────

    @Test
    @DisplayName("FVT-AC-01: POST /api/orders returns 201 with complete order JSON body")
    void createOrder_returns201WithBody() throws Exception {
        Order saved = buildOrder(1L, 101L, 250.00, "PENDING");
        when(orderService.createOrder(eq(101L), any(BigDecimal.class))).thenReturn(saved);

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":101,\"amount\":250.00}"))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.orderId").value(1))
            .andExpect(jsonPath("$.userId").value(101))
            .andExpect(jsonPath("$.amount").value(250.00))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    // ── FVT-AC-02: POST with missing body → 400 with error field ──────────────

    @Test
    @DisplayName("FVT-AC-02: POST /api/orders with missing body returns 400 with error field")
    void createOrder_missingBody_returns400WithErrorField() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── FVT-AC-03: GET /api/orders/user/{userId} → 200 with array body ────────

    @Test
    @DisplayName("FVT-AC-03: GET /api/orders/user/{userId} returns 200 with JSON array")
    void getOrdersByUser_returns200WithArray() throws Exception {
        List<Order> orders = List.of(
            buildOrder(1L, 101L, 100.00, "PENDING"),
            buildOrder(2L, 101L, 200.00, "SHIPPED")
        );
        when(orderService.getOrdersByUser(101L)).thenReturn(orders);

        mockMvc.perform(get("/api/orders/user/101"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].orderId").value(1))
            .andExpect(jsonPath("$[1].status").value("SHIPPED"));
    }

    // ── FVT-AC-04: GET /api/orders/user/{userId} with no orders → 200 + [] ────

    @Test
    @DisplayName("FVT-AC-04: GET /api/orders/user/{userId} returns 200 with empty array for unknown user")
    void getOrdersByUser_noOrders_returns200EmptyArray() throws Exception {
        when(orderService.getOrdersByUser(999L)).thenReturn(List.of());

        mockMvc.perform(get("/api/orders/user/999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── FVT-AC-05: GET /api/orders/{id}?userId= → 200 with single order ───────

    @Test
    @DisplayName("FVT-AC-05: GET /api/orders/{id}?userId= returns 200 with single order JSON")
    void getOrderById_returns200WithSingleOrder() throws Exception {
        Order order = buildOrder(5L, 101L, 75.00, "CONFIRMED");
        when(orderService.getOrderById(101L, 5L)).thenReturn(order);

        mockMvc.perform(get("/api/orders/5").param("userId", "101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(5))
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    // ── FVT-AC-06: GET /api/orders/{id} without userId → 400 ─────────────────

    @Test
    @DisplayName("FVT-AC-06: GET /api/orders/{id} without userId param returns 400")
    void getOrderById_missingUserId_returns400() throws Exception {
        mockMvc.perform(get("/api/orders/5"))
            .andExpect(status().isBadRequest());
    }

    // ── FVT-AC-07: GET /api/orders/{id} not found → 500 with error field ──────

    @Test
    @DisplayName("FVT-AC-07: GET /api/orders/{id} for non-existent order returns 500 with error JSON")
    void getOrderById_notFound_returns500WithErrorJson() throws Exception {
        when(orderService.getOrderById(anyLong(), anyLong()))
            .thenThrow(new RuntimeException("Order not found: id=999 on shard=2"));

        mockMvc.perform(get("/api/orders/999").param("userId", "101"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value(containsString("Order not found")));
    }

    // ── FVT-AC-08: PATCH /api/orders/{id}/status → 200 with updated order ─────

    @Test
    @DisplayName("FVT-AC-08: PATCH /api/orders/{id}/status returns 200 with updated status")
    void updateStatus_returns200WithUpdatedStatus() throws Exception {
        Order updated = buildOrder(1L, 101L, 250.00, "SHIPPED");
        when(orderService.updateOrderStatus(101L, 1L, "SHIPPED")).thenReturn(updated);

        mockMvc.perform(patch("/api/orders/1/status")
                .param("userId", "101")
                .param("status", "SHIPPED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SHIPPED"))
            .andExpect(jsonPath("$.orderId").value(1));
    }

    // ── FVT-AC-09: PATCH without status param → 400 ───────────────────────────

    @Test
    @DisplayName("FVT-AC-09: PATCH /api/orders/{id}/status without status param returns 400")
    void updateStatus_missingStatusParam_returns400() throws Exception {
        mockMvc.perform(patch("/api/orders/1/status")
                .param("userId", "101"))
            .andExpect(status().isBadRequest());
    }

    // ── FVT-AC-10: GET /api/orders/shard-info returns correct mapping ─────────

    @Test
    @DisplayName("FVT-AC-10: GET /api/orders/shard-info returns correct shard mapping for userId")
    void shardInfo_returnsCorrectMapping() throws Exception {
        // 101 % 3 = 2
        mockMvc.perform(get("/api/orders/shard-info").param("userId", "101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(101))
            .andExpect(jsonPath("$.shardIndex").value(2))
            .andExpect(jsonPath("$.totalShards").value(3));
    }

    // ── FVT-AC-11: GET /api/orders/shard-info without userId → 400 ───────────

    @Test
    @DisplayName("FVT-AC-11: GET /api/orders/shard-info without userId returns 400")
    void shardInfo_missingUserId_returns400() throws Exception {
        mockMvc.perform(get("/api/orders/shard-info"))
            .andExpect(status().isBadRequest());
    }

    // ── FVT-AC-12: Error response always contains the 'error' field ──────────

    @Test
    @DisplayName("FVT-AC-12: All 500 error responses contain a top-level 'error' JSON field")
    void errorResponse_alwaysContainsErrorField() throws Exception {
        when(orderService.getOrdersByUser(anyLong()))
            .thenThrow(new RuntimeException("unexpected failure"));

        mockMvc.perform(get("/api/orders/user/1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── FVT-AC-13: Response Content-Type is application/json ─────────────────

    @Test
    @DisplayName("FVT-AC-13: All successful responses have Content-Type application/json")
    void successResponses_haveJsonContentType() throws Exception {
        when(orderService.getOrdersByUser(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/orders/user/1"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Order buildOrder(long id, long userId, double amount, String status) {
        Order o = new Order(userId, BigDecimal.valueOf(amount));
        o.setOrderId(id);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
