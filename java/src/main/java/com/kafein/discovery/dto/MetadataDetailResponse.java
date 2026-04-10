package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Full detail response for GET /metadata/{id}.
 * Equivalent to the Pydantic MetadataDetailResponse in app/metadata/router.py.
 */
public class MetadataDetailResponse {

    @JsonProperty("metadata_id")
    private String metadataId;

    @JsonProperty("database_name")
    private String databaseName;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("tables")
    private List<TableDetailOut> tables;

    public MetadataDetailResponse() {}

    public MetadataDetailResponse(String metadataId, String databaseName,
                                   String createdAt, List<TableDetailOut> tables) {
        this.metadataId = metadataId;
        this.databaseName = databaseName;
        this.createdAt = createdAt;
        this.tables = tables;
    }

    public String getMetadataId() { return metadataId; }
    public void setMetadataId(String metadataId) { this.metadataId = metadataId; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public List<TableDetailOut> getTables() { return tables; }
    public void setTables(List<TableDetailOut> tables) { this.tables = tables; }
}
