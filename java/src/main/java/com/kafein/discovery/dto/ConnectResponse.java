package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response body for POST /db/metadata.
 * Equivalent to the Pydantic ConnectResponse in app/metadata/router.py.
 */
public class ConnectResponse {

    @JsonProperty("metadata_id")
    private String metadataId;

    @JsonProperty("database_name")
    private String databaseName;

    @JsonProperty("table_count")
    private int tableCount;

    @JsonProperty("tables")
    private List<TableOut> tables;

    public ConnectResponse() {}

    public ConnectResponse(String metadataId, String databaseName, int tableCount, List<TableOut> tables) {
        this.metadataId = metadataId;
        this.databaseName = databaseName;
        this.tableCount = tableCount;
        this.tables = tables;
    }

    public String getMetadataId() { return metadataId; }
    public void setMetadataId(String metadataId) { this.metadataId = metadataId; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public int getTableCount() { return tableCount; }
    public void setTableCount(int tableCount) { this.tableCount = tableCount; }

    public List<TableOut> getTables() { return tables; }
    public void setTables(List<TableOut> tables) { this.tables = tables; }
}
