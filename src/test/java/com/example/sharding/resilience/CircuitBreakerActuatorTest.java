package com.example.sharding.resilience;

import com.example.sharding.controller.GlobalExceptionHandler;
import com.example.sharding.controller.OrderController;
import com.example.sharding.exception.OrderNotFoundException;
import com.example.sharding.service.OrderService;
import com.example.sharding.service.OrderService.OrderResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc tests verifying that the {@link GlobalExceptionHandler} correctly
 * maps circuit-breaker and not-found exceptions to the right HTTP status codes.
 *
 * <ul>
 *   <li>HTTP 503 + {@code Retry-After: 30} when circuit is OPEN
 *       ({@link CallNotPermittedException})</li>
 *   <li>HTTP 404 when order is not found
 *       ({@link OrderNotFoundException})</li>
 * </ul>
 */
@WebMvcTest(controllers = {OrderController.class, GlobalExceptionHandler.class})
@DisplayName("Circuit Breaker — HTTP Contract Tests")
public class CircuitBreakerActuatorTest {

    @Autowired MockMvc mockMvc;
    @MockBean  OrderService orderService;

    // ── Helper to build a CallNotPermittedException ───────────────────────────

    private static CallNotPermittedException openCircuitException() {
        CircuitBreaker cb = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().build())
                .circuitBreaker("shard2-primary");
        cb.transitionToOpenState();
        return CallNotPermittedException.createCallNotPermittedException(cb);
    }

    // ── CB-HTTP-01: 503 when circuit breaker is OPEN ──────────────────────────

    @Test
    @DisplayName("CB-HTTP-01: POST /api/orders returns 503 when circuit breaker is OPEN")
    void createOrder_returns503_whenCircuitOpen() throws Exception {
        when(orderService.createOrder(anyLong(), any(BigDecimal.class), any()))
                .thenThrow(openCircuitException());

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":101,\"amount\":50.00}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "30"))
            .andExpect(jsonPath("$.error").value(containsString("Circuit breaker is OPEN")));
    }

    // ── CB-HTTP-02: 503 response contains error and detail fields ─────────────

    @Test
    @DisplayName("CB-HTTP-02: 503 response body has 'error' and 'detail' fields")
    void circuitOpen_responseBody_hasRequiredFields() throws Exception {
        when(orderService.createOrder(anyLong(), any(BigDecimal.class), any()))
                .thenThrow(openCircuitException());

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":101,\"amount\":50.00}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").isNotEmpty())
            .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    // ── CB-HTTP-03: Retry-After header is exactly "30" ────────────────────────

    @Test
    @DisplayName("CB-HTTP-03: 503 response sets Retry-After header to 30 seconds")
    void circuitOpen_retryAfterHeader_is30() throws Exception {
        when(orderService.createOrder(anyLong(), any(BigDecimal.class), any()))
                .thenThrow(openCircuitException());

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":101,\"amount\":50.00}"))
            .andExpect(header().string("Retry-After", "30"));
    }

    // ── CB-HTTP-04: 404 when order not found ──────────────────────────────────

    @Test
    @DisplayName("CB-HTTP-04: GET /api/orders/{id} returns 404 when order not found")
    void getOrderById_returns404_whenNotFound() throws Exception {
        when(orderService.getOrderById(anyLong(), anyLong()))
                .thenThrow(new OrderNotFoundException(42L, 0));

        mockMvc.perform(get("/api/orders/42").param("userId", "3"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value(containsString("Order not found")));
    }

    // ── CB-HTTP-05: 404 response has correct error message with orderId ────────

    @Test
    @DisplayName("CB-HTTP-05: 404 error message contains the specific order ID that was missing")
    void notFound_errorMessage_containsOrderId() throws Exception {
        when(orderService.getOrderById(anyLong(), anyLong()))
                .thenThrow(new OrderNotFoundException(12345L, 1));

        mockMvc.perform(get("/api/orders/12345").param("userId", "4"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value(containsString("12345")));
    }

    // ── CB-HTTP-06: GET /api/orders/{id} circuit open → 503 ──────────────────

    @Test
    @DisplayName("CB-HTTP-06: GET /api/orders/{id} returns 503 when replica circuit is OPEN")
    void getOrderById_returns503_whenCircuitOpen() throws Exception {
        when(orderService.getOrderById(anyLong(), anyLong()))
                .thenThrow(openCircuitException());

        mockMvc.perform(get("/api/orders/1").param("userId", "101"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "30"));
    }

    // ── CB-HTTP-07: 500 still returned for unexpected errors ──────────────────

    @Test
    @DisplayName("CB-HTTP-07: Unexpected RuntimeException still returns 500")
    void unexpectedError_returns500() throws Exception {
        when(orderService.getOrdersByUser(anyLong()))
                .thenThrow(new RuntimeException("unexpected DB error"));

        mockMvc.perform(get("/api/orders/user/101"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value(containsString("unexpected DB error")));
    }

    // ── CB-HTTP-08: PATCH /api/orders/{id}/status → 503 when circuit open ─────

    @Test
    @DisplayName("CB-HTTP-08: PATCH /api/orders/{id}/status returns 503 when primary circuit is OPEN")
    void updateStatus_returns503_whenCircuitOpen() throws Exception {
        when(orderService.updateOrderStatus(anyLong(), anyLong(), any()))
                .thenThrow(openCircuitException());

        mockMvc.perform(patch("/api/orders/1/status")
                .param("userId", "101")
                .param("status", "SHIPPED"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "30"));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static com.example.sharding.entity.Order buildOrder(
            long id, long userId, double amount, String status) {
        com.example.sharding.entity.Order o =
                new com.example.sharding.entity.Order(userId, BigDecimal.valueOf(amount));
        o.setOrderId(id);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
