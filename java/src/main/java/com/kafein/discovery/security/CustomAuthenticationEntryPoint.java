package com.kafein.discovery.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Custom authentication entry point.
 * Handles cases where no authentication token is provided.
 * Provides a "friendly" Turkish error message matching the Python implementation.
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, String> body = Map.of(
            "detail", "Bu kaynağa erişmek için yetkilendirme gereklidir. Lütfen /auth endpoint'ini kullanarak geçerli bir JWT token edinin."
        );

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
