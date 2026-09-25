package com.example.sharding.controller;

import com.example.sharding.entity.Order;
import com.example.sharding.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc tests for {@link OrderController}.
 * The service layer is fully mocked — no database or Spring Data context needed.
 */
@WebMvcTest(OrderController.class)
@DisplayName("OrderController — MockMvc")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    // ── POST /api/orders ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/orders → 201 with saved order in body")
    void createOrder_returns201() throws Exception {
        Order saved = buildOrder(1L, 101L, new BigDecimal("250.00"), "PENDING");
        when(orderService.createOrder(eq(101L), any(BigDecimal.class))).thenReturn(saved);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "userId": 101, "amount": 250.00 }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.userId").value(101))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/orders with missing body → 400")
    void createOrder_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/orders/user/{userId} ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/orders/user/101 → 200 with list of orders")
    void getOrdersByUser_returnsList() throws Exception {
        long userId = 101L;
        List<Order> orders = List.of(
                buildOrder(1L, userId, new BigDecimal("100.00"), "PENDING"),
                buildOrder(2L, userId, new BigDecimal("200.00"), "SHIPPED")
        );
        when(orderService.getOrdersByUser(userId)).thenReturn(orders);

        mockMvc.perform(get("/api/orders/user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].orderId").value(1))
                .andExpect(jsonPath("$[1].orderId").value(2))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("SHIPPED"));
    }

    @Test
    @DisplayName("GET /api/orders/user/999 → 200 with empty list")
    void getOrdersByUser_emptyList() throws Exception {
        when(orderService.getOrdersByUser(999L)).thenReturn(List.of());

        mockMvc.perform(get("/api/orders/user/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── GET /api/orders/{orderId} ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/orders/1?userId=101 → 200 with order")
    void getOrderById_returnsOrder() throws Exception {
        Order order = buildOrder(1L, 101L, new BigDecimal("75.00"), "CONFIRMED");
        when(orderService.getOrderById(101L, 1L)).thenReturn(order);

        mockMvc.perform(get("/api/orders/{orderId}", 1L)
                        .param("userId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("GET /api/orders/999?userId=101 → 500 when order not found")
    void getOrderById_notFound_returns500() throws Exception {
        when(orderService.getOrderById(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Order not found: id=999 on shard=2"));

        mockMvc.perform(get("/api/orders/{orderId}", 999L)
                        .param("userId", "101"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("GET /api/orders/{orderId} without userId param → 400")
    void getOrderById_missingUserIdParam_returns400() throws Exception {
        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /api/orders/{orderId}/status ────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/orders/1/status?userId=101&status=SHIPPED → 200")
    void updateStatus_returnsUpdatedOrder() throws Exception {
        Order updated = buildOrder(1L, 101L, new BigDecimal("250.00"), "SHIPPED");
        when(orderService.updateOrderStatus(101L, 1L, "SHIPPED")).thenReturn(updated);

        mockMvc.perform(patch("/api/orders/{orderId}/status", 1L)
                        .param("userId", "101")
                        .param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    @DisplayName("PATCH /api/orders/999/status → 500 when order not found")
    void updateStatus_notFound_returns500() throws Exception {
        when(orderService.updateOrderStatus(anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("Order not found: id=999"));

        mockMvc.perform(patch("/api/orders/{orderId}/status", 999L)
                        .param("userId", "101")
                        .param("status", "CANCELLED"))
                .andExpect(status().isInternalServerError());
    }

    // ── GET /api/orders/shard-info ────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/orders/shard-info?userId=101 → shard=2, totalShards=3")
    void shardInfo_returnsCorrectMapping() throws Exception {
        // 101 % 3 = 2
        mockMvc.perform(get("/api/orders/shard-info")
                        .param("userId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(101))
                .andExpect(jsonPath("$.shardIndex").value(2))
                .andExpect(jsonPath("$.totalShards").value(3));
    }

    @Test
    @DisplayName("GET /api/orders/shard-info?userId=0 → shard=0")
    void shardInfo_userId0_returnsShard0() throws Exception {
        mockMvc.perform(get("/api/orders/shard-info")
                        .param("userId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shardIndex").value(0));
    }

    @Test
    @DisplayName("GET /api/orders/shard-info without userId → 400")
    void shardInfo_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/api/orders/shard-info"))
                .andExpect(status().isBadRequest());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Order buildOrder(long orderId, long userId, BigDecimal amount, String status) {
        Order o = new Order(userId, amount);
        o.setOrderId(orderId);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
