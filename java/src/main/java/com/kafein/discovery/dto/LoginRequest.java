package com.kafein.discovery.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /auth.
 * Equivalent to the Pydantic LoginRequest in app/auth/router.py.
 */
public class LoginRequest {

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "password is required")
    private String password;

    public LoginRequest() {}

    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
