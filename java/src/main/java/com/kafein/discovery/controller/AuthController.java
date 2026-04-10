package com.kafein.discovery.controller;

import com.kafein.discovery.dto.LoginRequest;
import com.kafein.discovery.dto.TokenResponse;
import com.kafein.discovery.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication controller.
 * Exposes POST /auth – accepts username/password and returns a JWT Bearer token.
 *
 * Equivalent to app/auth/router.py in the Python FastAPI project.
 */
@RestController
@Tag(name = "Authentication", description = "Obtain a JWT Bearer token")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /auth
     * Validates BASIC_AUTH_USERNAME / BASIC_AUTH_PASSWORD and issues a JWT.
     *
     * @param request  Login credentials (username + password)
     * @return JWT access token with expiry information
     */
    @Operation(
            summary = "Authenticate and obtain a JWT token",
            description = "Provide the configured BASIC_AUTH_USERNAME and BASIC_AUTH_PASSWORD "
                    + "to receive a Bearer token for use with all other endpoints."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Successful authentication",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error – missing fields",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Incorrect username or password",
                    content = @Content)
    })
    @PostMapping(value = "/auth",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
