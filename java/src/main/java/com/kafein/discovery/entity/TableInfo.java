package com.kafein.discovery.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Maps to table: tables_info
 * Represents a table discovered in the target database.
 * Equivalent to the SQLAlchemy TableInfo model in app/models.py.
 */
@Entity
@Table(name = "tables_info")
public class TableInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "metadata_id", nullable = false)
    private UUID metadataId;

    @Column(name = "table_name", nullable = false, length = 255)
    private String tableName;

    @Column(name = "schema_name", nullable = false, length = 255)
    private String schemaName = "public";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metadata_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private MetadataRecord metadataRecord;

    @OneToMany(mappedBy = "tableInfo", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ColumnInfo> columns = new ArrayList<>();

    // ---------- Getters & Setters ----------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getMetadataId() { return metadataId; }
    public void setMetadataId(UUID metadataId) { this.metadataId = metadataId; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public MetadataRecord getMetadataRecord() { return metadataRecord; }
    public void setMetadataRecord(MetadataRecord metadataRecord) { this.metadataRecord = metadataRecord; }

    public List<ColumnInfo> getColumns() { return columns; }
    public void setColumns(List<ColumnInfo> columns) { this.columns = columns; }
}
