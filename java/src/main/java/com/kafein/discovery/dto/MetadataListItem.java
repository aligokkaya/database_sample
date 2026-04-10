package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Summary item returned in GET /metadata.
 * Equivalent to the Pydantic MetadataListItem in app/metadata/router.py.
 */
public class MetadataListItem {

    @JsonProperty("metadata_id")
    private String metadataId;

    @JsonProperty("database_name")
    private String databaseName;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("table_count")
    private int tableCount;

    public MetadataListItem() {}

    public MetadataListItem(String metadataId, String databaseName, String createdAt, int tableCount) {
        this.metadataId = metadataId;
        this.databaseName = databaseName;
        this.createdAt = createdAt;
        this.tableCount = tableCount;
    }

    public String getMetadataId() { return metadataId; }
    public void setMetadataId(String metadataId) { this.metadataId = metadataId; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public int getTableCount() { return tableCount; }
    public void setTableCount(int tableCount) { this.tableCount = tableCount; }
}
