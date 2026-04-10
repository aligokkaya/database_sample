package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Table summary used in the connect response.
 * Equivalent to the Pydantic TableOut in app/metadata/router.py.
 */
public class TableOut {

    @JsonProperty("table_name")
    private String tableName;

    @JsonProperty("columns")
    private List<ColumnOut> columns;

    public TableOut() {}

    public TableOut(String tableName, List<ColumnOut> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public List<ColumnOut> getColumns() { return columns; }
    public void setColumns(List<ColumnOut> columns) { this.columns = columns; }
}
