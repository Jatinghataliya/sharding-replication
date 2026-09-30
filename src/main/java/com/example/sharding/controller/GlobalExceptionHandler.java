package com.example.sharding.controller;

import com.example.sharding.exception.OrderNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler.
 *
 * <ul>
 *   <li>400 Bad Request  — missing body / param, invalid idempotency key</li>
 *   <li>404 Not Found    — order not found on its shard</li>
 *   <li>503 Unavailable  — shard circuit breaker is OPEN (fail-fast)</li>
 *   <li>500 Server Error — unhandled RuntimeException</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Missing request body or missing required query param → 400. */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Invalid idempotency key (blank, too long) → 400.
     * Thrown by {@link com.example.sharding.idempotency.IdempotencyService#validateKey}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Order not found on its shard → 404.
     * Using a specific exception keeps 404 semantics clean and prevents
     * the circuit breaker from counting "not found" as an infrastructure fault.
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleOrderNotFound(OrderNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Shard circuit breaker is OPEN — the shard's database is unreachable
     * and fail-fast protection is active → 503 Service Unavailable.
     *
     * <p>The {@code Retry-After} header tells the client how long to wait
     * before retrying (matching the circuit-breaker's
     * {@code waitDurationInOpenState} from application.yml: 30 s).
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Map<String, String>> handleCircuitBreakerOpen(
            CallNotPermittedException ex) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .body(Map.of(
                        "error",  "Shard is temporarily unavailable. Circuit breaker is OPEN.",
                        "detail", ex.getMessage()
                ));
    }

    /** All other unhandled runtime exceptions → 500. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage()));
    }
}
