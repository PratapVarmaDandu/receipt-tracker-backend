package com.receipttracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(
            DataIntegrityViolationException e) {
        String cause = e.getMostSpecificCause().getMessage();
        log.warn("Data integrity violation (suppressed from response): {}", cause);

        // Return a safe, schema-free message
        String safeMsg = "Request could not be completed due to a data constraint";
        if (cause != null) {
            String lower = cause.toLowerCase();
            if (lower.contains("email")) {
                safeMsg = "Email address is invalid or already in use";
            } else if (lower.contains("unique") || lower.contains("duplicate")) {
                safeMsg = "A record with this value already exists";
            }
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", safeMsg));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.debug("Type mismatch for parameter '{}': {}", e.getName(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid value for parameter '" + e.getName() + "'"));
    }
}
