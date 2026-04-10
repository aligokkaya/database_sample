package com.kafein.discovery.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc / Swagger UI configuration.
 * Registers the BearerAuth security scheme used by all protected endpoints.
 *
 * After starting the application, Swagger UI is available at:
 *   http://localhost:8080/swagger-ui.html
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "LLM-Based Database Data Discovery API",
                version = "1.0.0",
                description = "Java Spring Boot port of the Python FastAPI data-discovery service. "
                        + "Provides endpoints for connecting to PostgreSQL databases, harvesting "
                        + "schema metadata, and classifying columns for PII using an LLM."
        )
)
@SecurityScheme(
        name = "BearerAuth",
        description = "JWT Bearer token – obtain via POST /auth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER
)
public class OpenApiConfig {
    // Configuration is entirely annotation-driven; no bean methods required.
}
