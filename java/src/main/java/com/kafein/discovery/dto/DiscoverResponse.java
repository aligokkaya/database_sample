package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response body for POST /classify/discover.
 * Matches Python DiscoverResponse / DiscoverTableResult / DiscoverColumnResult exactly.
 */
public class DiscoverResponse {

    @JsonProperty("metadata_id")
    private String metadataId;

    @JsonProperty("database_name")
    private String databaseName;

    @JsonProperty("total_columns")
    private int totalColumns;

    @JsonProperty("pii_columns")
    private int piiColumns;

    private List<TableResult> tables;

    public DiscoverResponse() {}

    public DiscoverResponse(String metadataId, String databaseName,
                            int totalColumns, int piiColumns,
                            List<TableResult> tables) {
        this.metadataId   = metadataId;
        this.databaseName = databaseName;
        this.totalColumns = totalColumns;
        this.piiColumns   = piiColumns;
        this.tables       = tables;
    }

    public String getMetadataId()              { return metadataId; }
    public void   setMetadataId(String v)      { this.metadataId = v; }

    public String getDatabaseName()            { return databaseName; }
    public void   setDatabaseName(String v)    { this.databaseName = v; }

    public int  getTotalColumns()              { return totalColumns; }
    public void setTotalColumns(int v)         { this.totalColumns = v; }

    public int  getPiiColumns()                { return piiColumns; }
    public void setPiiColumns(int v)           { this.piiColumns = v; }

    public List<TableResult> getTables()       { return tables; }
    public void setTables(List<TableResult> v) { this.tables = v; }

    // ── Nested: TableResult ───────────────────────────────────────────────────

    public static class TableResult {

        @JsonProperty("table_name")
        private String tableName;

        @JsonProperty("pii_count")
        private int piiCount;

        private List<ColumnResult> columns;

        public TableResult() {}

        public TableResult(String tableName, int piiCount, List<ColumnResult> columns) {
            this.tableName = tableName;
            this.piiCount  = piiCount;
            this.columns   = columns;
        }

        public String getTableName()              { return tableName; }
        public void   setTableName(String v)      { this.tableName = v; }

        public int  getPiiCount()                 { return piiCount; }
        public void setPiiCount(int v)            { this.piiCount = v; }

        public List<ColumnResult> getColumns()    { return columns; }
        public void setColumns(List<ColumnResult> v) { this.columns = v; }
    }

    // ── Nested: ColumnResult ──────────────────────────────────────────────────

    public static class ColumnResult {

        @JsonProperty("column_id")
        private String columnId;

        @JsonProperty("column_name")
        private String columnName;

        @JsonProperty("is_pii")
        private boolean isPii;

        private String category;

        public ColumnResult() {}

        public ColumnResult(String columnId, String columnName,
                            boolean isPii, String category) {
            this.columnId   = columnId;
            this.columnName = columnName;
            this.isPii      = isPii;
            this.category   = category;
        }

        public String getColumnId()          { return columnId; }
        public void   setColumnId(String v)  { this.columnId = v; }

        public String getColumnName()           { return columnName; }
        public void   setColumnName(String v)   { this.columnName = v; }

        @JsonProperty("is_pii")
        public boolean isPii()               { return isPii; }
        public void    setPii(boolean v)     { this.isPii = v; }

        public String getCategory()          { return category; }
        public void   setCategory(String v)  { this.category = v; }
    }
}
