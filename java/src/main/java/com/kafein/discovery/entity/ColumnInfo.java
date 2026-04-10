package com.kafein.discovery.entity;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Maps to table: columns_info
 * Represents a column discovered in the target database.
 * Equivalent to the SQLAlchemy ColumnInfo model in app/models.py.
 */
@Entity
@Table(name = "columns_info")
public class ColumnInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "table_id", nullable = false)
    private UUID tableId;

    @Column(name = "metadata_id", nullable = false)
    private UUID metadataId;

    @Column(name = "column_name", nullable = false, length = 255)
    private String columnName;

    @Column(name = "data_type", nullable = false, length = 255)
    private String dataType;

    @Column(name = "ordinal_position", nullable = false)
    private Integer ordinalPosition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private TableInfo tableInfo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metadata_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private MetadataRecord metadataRecord;

    // ---------- Getters & Setters ----------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTableId() { return tableId; }
    public void setTableId(UUID tableId) { this.tableId = tableId; }

    public UUID getMetadataId() { return metadataId; }
    public void setMetadataId(UUID metadataId) { this.metadataId = metadataId; }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public Integer getOrdinalPosition() { return ordinalPosition; }
    public void setOrdinalPosition(Integer ordinalPosition) { this.ordinalPosition = ordinalPosition; }

    public TableInfo getTableInfo() { return tableInfo; }
    public void setTableInfo(TableInfo tableInfo) { this.tableInfo = tableInfo; }

    public MetadataRecord getMetadataRecord() { return metadataRecord; }
    public void setMetadataRecord(MetadataRecord metadataRecord) { this.metadataRecord = metadataRecord; }
}
