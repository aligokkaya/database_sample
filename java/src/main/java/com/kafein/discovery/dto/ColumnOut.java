package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Column summary used in connect and detail responses.
 * Equivalent to the Pydantic ColumnOut in app/metadata/router.py.
 */
public class ColumnOut {

    @JsonProperty("column_id")
    private String columnId;

    @JsonProperty("column_name")
    private String columnName;

    @JsonProperty("data_type")
    private String dataType;

    public ColumnOut() {}

    public ColumnOut(String columnId, String columnName, String dataType) {
        this.columnId = columnId;
        this.columnName = columnName;
        this.dataType = dataType;
    }

    public String getColumnId() { return columnId; }
    public void setColumnId(String columnId) { this.columnId = columnId; }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
}
