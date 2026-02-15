package com.trading.scanner.controller;

import jakarta.persistence.OptimisticLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLockException(OptimisticLockException ex) {
        return new ResponseEntity<>(
            Map.of("success", false, "message", "State was modified by another request. Please retry."),
            HttpStatus.CONFLICT
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException ex) {
        // Handle specific case of cycling already in progress
        if (ex.getMessage() != null && ex.getMessage().contains("cycle is already in progress")) {
            return new ResponseEntity<>(
                Map.of("success", false, "message", ex.getMessage()),
                HttpStatus.CONFLICT
            );
        }
        // Handle other illegal state issues as internal server error
        return new ResponseEntity<>(
            Map.of("success", false, "message", "An unexpected server error occurred: " + ex.getMessage()),
            HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
