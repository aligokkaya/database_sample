package com.kafein.discovery.security;

import com.kafein.discovery.config.AppConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/**
 * JWT utility component.
 * Generates and validates JSON Web Tokens using JJWT 0.12.x.
 * Equivalent to the python-jose JWT helpers in app/auth/router.py.
 */
@Component
public class JwtUtil {

    private final AppConfig appConfig;

    public JwtUtil(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * Derive a 256-bit HMAC-SHA key from the configured secret string.
     * Uses SHA-256 so that secrets of any length become a valid 32-byte key.
     */
    private SecretKey getSigningKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(
                    appConfig.getJwtSecret().getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Generate a JWT token for the given username.
     * Sets 'sub' claim and expiry matching JWT_EXPIRY_HOURS.
     */
    public String generateToken(String username) {
        long expiryMillis = (long) appConfig.getJwtExpiryHours() * 3600 * 1000;
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiryMillis);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Validate the token and return its claims.
     * Throws JwtException if the token is invalid or expired.
     */
    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extract the subject (username) from a valid token.
     * Throws JwtException if the token is invalid.
     */
    public String extractUsername(String token) {
        return validateToken(token).getSubject();
    }

    /**
     * Return expiry seconds for the token response payload.
     */
    public long getExpirySeconds() {
        return (long) appConfig.getJwtExpiryHours() * 3600;
    }
}
