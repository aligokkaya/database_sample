package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for POST /auth.
 * Equivalent to the Pydantic TokenResponse in app/auth/router.py.
 */
public class TokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("token_type")
    private String tokenType = "bearer";

    @JsonProperty("expires_in")
    private long expiresIn;

    public TokenResponse() {}

    public TokenResponse(String accessToken, long expiresIn) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }

    public long getExpiresIn() { return expiresIn; }
    public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }
}
