package com.kafein.discovery.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Maps to table: metadata_records
 * Equivalent to the SQLAlchemy MetadataRecord model in app/models.py.
 */
@Entity
@Table(name = "metadata_records")
public class MetadataRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "database_name", nullable = false, length = 255)
    private String databaseName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToOne(mappedBy = "metadataRecord", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private DbConnection dbConnection;

    @OneToMany(mappedBy = "metadataRecord", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TableInfo> tables = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    // ---------- Getters & Setters ----------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public DbConnection getDbConnection() { return dbConnection; }
    public void setDbConnection(DbConnection dbConnection) { this.dbConnection = dbConnection; }

    public List<TableInfo> getTables() { return tables; }
    public void setTables(List<TableInfo> tables) { this.tables = tables; }
}
