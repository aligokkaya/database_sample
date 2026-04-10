package com.kafein.discovery.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * General application configuration.
 * Holds environment-bound properties and shared beans.
 */
@Configuration
public class AppConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiry-hours:24}")
    private int jwtExpiryHours;

    @Value("${app.auth.username}")
    private String authUsername;

    @Value("${app.auth.password}")
    private String authPassword;

    @Value("${app.encryption.key}")
    private String encryptionKey;

    @Value("${app.llm.base-url}")
    private String llmBaseUrl;

    @Value("${app.llm.api-key:ollama}")
    private String llmApiKey;

    @Value("${app.llm.model:llama3.2:latest}")
    private String llmModel;

    // ---------- Getters ----------

    public String getJwtSecret() {
        return jwtSecret;
    }

    public int getJwtExpiryHours() {
        return jwtExpiryHours;
    }

    public String getAuthUsername() {
        return authUsername;
    }

    public String getAuthPassword() {
        return authPassword;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public String getLlmApiKey() {
        return llmApiKey;
    }

    public String getLlmModel() {
        return llmModel;
    }

    // ---------- Beans ----------

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
