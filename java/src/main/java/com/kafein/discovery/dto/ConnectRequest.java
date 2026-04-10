package com.kafein.discovery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /db/metadata.
 * Equivalent to the Pydantic ConnectRequest in app/metadata/router.py.
 */
public class ConnectRequest {

    @NotBlank(message = "host is required")
    private String host;

    @NotNull(message = "port is required")
    private Integer port = 5432;

    @NotBlank(message = "database is required")
    private String database;

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "password is required")
    private String password;

    public ConnectRequest() {}

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
