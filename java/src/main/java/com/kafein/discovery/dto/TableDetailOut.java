package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Detailed table info returned in GET /metadata/{id}.
 * Equivalent to the Pydantic TableDetailOut in app/metadata/router.py.
 */
public class TableDetailOut {

    @JsonProperty("table_id")
    private String tableId;

    @JsonProperty("table_name")
    private String tableName;

    @JsonProperty("schema_name")
    private String schemaName;

    @JsonProperty("columns")
    private List<ColumnOut> columns;

    public TableDetailOut() {}

    public TableDetailOut(String tableId, String tableName, String schemaName, List<ColumnOut> columns) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.schemaName = schemaName;
        this.columns = columns;
    }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public List<ColumnOut> getColumns() { return columns; }
    public void setColumns(List<ColumnOut> columns) { this.columns = columns; }
}
