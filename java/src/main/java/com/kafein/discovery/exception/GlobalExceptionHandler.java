package com.kafein.discovery.exception;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler.
 * Maps exceptions to consistent JSON error responses.
 *
 * Mirrors the HTTPException / validation error handling in FastAPI, which returns:
 *   { "detail": "..." }
 * and for validation errors in Pydantic:
 *   { "detail": [ { "loc": [...], "msg": "...", "type": "..." } ] }
 *
 * For simplicity, this handler normalises all errors to:
 *   { "status": <httpCode>, "error": "<httpStatus>", "message": "...", "timestamp": "..." }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -------------------------------------------------------------------------
    // Spring ResponseStatusException (used throughout services)
    // -------------------------------------------------------------------------

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return buildResponse(ex.getStatusCode().value(), ex.getReason());
    }

    // -------------------------------------------------------------------------
    // JPA / entity not found
    // -------------------------------------------------------------------------

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEntityNotFound(EntityNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Bean Validation (@Valid failures)
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST.value(),
                message.isEmpty() ? "Validation failed" : message);
    }

    // -------------------------------------------------------------------------
    // Malformed JSON body
    // -------------------------------------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(
            HttpMessageNotReadableException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST.value(), "Malformed JSON request body");
    }

    // -------------------------------------------------------------------------
    // Illegal argument (UUID parse errors etc.)
    // -------------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Fallback – unexpected runtime errors
    // -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred: " + ex.getMessage());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ResponseEntity<Map<String, Object>> buildResponse(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", HttpStatus.resolve(status) != null
                ? HttpStatus.resolve(status).getReasonPhrase()
                : "Error");
        body.put("message", message != null ? message : "No message available");
        body.put("timestamp", OffsetDateTime.now().toString());

        return ResponseEntity.status(status).body(body);
    }
}
