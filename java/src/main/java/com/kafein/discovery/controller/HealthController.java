package com.kafein.discovery.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Health-check endpoint.
 * GET /health returns a simple status payload and requires no authentication.
 *
 * Equivalent to the /health route in app/main.py.
 */
@RestController
@Tag(name = "Health", description = "Service health check")
public class HealthController {

    /**
     * GET /health
     * Public endpoint – no JWT required (configured in SecurityConfig).
     *
     * @return JSON payload with status "ok" and the current server time
     */
    @Operation(
            summary = "Health check",
            description = "Returns 200 OK when the service is running. No authentication required."
    )
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "data-discovery-api",
                "timestamp", OffsetDateTime.now().toString()
        );
    }
}
