package com.kafein.discovery.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps to table: db_connections
 * Stores the target database connection details (password encrypted).
 * Equivalent to the SQLAlchemy DbConnection model in app/models.py.
 */
@Entity
@Table(name = "db_connections")
public class DbConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "metadata_id", nullable = false)
    private UUID metadataId;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false, length = 10)
    private String port;

    @Column(name = "database_name", nullable = false, length = 255)
    private String databaseName;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "encrypted_password", nullable = false, columnDefinition = "TEXT")
    private String encryptedPassword;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metadata_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private MetadataRecord metadataRecord;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    // ---------- Getters & Setters ----------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getMetadataId() { return metadataId; }
    public void setMetadataId(UUID metadataId) { this.metadataId = metadataId; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getPort() { return port; }
    public void setPort(String port) { this.port = port; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public MetadataRecord getMetadataRecord() { return metadataRecord; }
    public void setMetadataRecord(MetadataRecord metadataRecord) { this.metadataRecord = metadataRecord; }
}
