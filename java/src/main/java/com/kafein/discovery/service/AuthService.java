package com.kafein.discovery.service;

import com.kafein.discovery.config.AppConfig;
import com.kafein.discovery.dto.LoginRequest;
import com.kafein.discovery.dto.TokenResponse;
import com.kafein.discovery.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authentication service.
 * Validates credentials against BASIC_AUTH_USERNAME / BASIC_AUTH_PASSWORD env vars
 * and issues a JWT token.
 *
 * Equivalent to the login endpoint logic in app/auth/router.py.
 */
@Service
public class AuthService {

    private final AppConfig appConfig;
    private final JwtUtil jwtUtil;

    public AuthService(AppConfig appConfig, JwtUtil jwtUtil) {
        this.appConfig = appConfig;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Authenticate the user and return a JWT token.
     * Throws 401 if credentials are incorrect.
     */
    public TokenResponse login(LoginRequest request) {
        if (!appConfig.getAuthUsername().equals(request.getUsername())
                || !appConfig.getAuthPassword().equals(request.getPassword())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Incorrect username or password"
            );
        }

        String token = jwtUtil.generateToken(request.getUsername());
        long expiresIn = jwtUtil.getExpirySeconds();
        return new TokenResponse(token, expiresIn);
    }
}
