package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response body for POST /classify/discover.
 * Contains a full PII discovery report grouped by table.
 * Equivalent to DiscoverResponse in app/classify/router.py.
 */
public class DiscoverResponse {

    @JsonProperty("metadata_id")
    private String metadataId;

    @JsonProperty("database_name")
    private String databaseName;

    @JsonProperty("sample_count")
    private int sampleCount;

    private Summary summary;

    private List<TableResult> tables;

    // ---------- Constructors ----------

    public DiscoverResponse() {}

    public DiscoverResponse(String metadataId, String databaseName, int sampleCount,
                            Summary summary, List<TableResult> tables) {
        this.metadataId = metadataId;
        this.databaseName = databaseName;
        this.sampleCount = sampleCount;
        this.summary = summary;
        this.tables = tables;
    }

    // ---------- Getters & Setters ----------

    public String getMetadataId() { return metadataId; }
    public void setMetadataId(String metadataId) { this.metadataId = metadataId; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }

    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public List<TableResult> getTables() { return tables; }
    public void setTables(List<TableResult> tables) { this.tables = tables; }

    // =========================================================================
    // Nested: Summary
    // =========================================================================

    public static class Summary {

        @JsonProperty("total_columns")
        private int totalColumns;

        @JsonProperty("skipped_columns")
        private int skippedColumns;

        @JsonProperty("rule_based_columns")
        private int ruleBasedColumns;

        @JsonProperty("llm_scanned_columns")
        private int llmScannedColumns;

        @JsonProperty("pii_columns")
        private int piiColumns;

        @JsonProperty("non_pii_columns")
        private int nonPiiColumns;

        public Summary() {}

        public Summary(int totalColumns, int skippedColumns, int ruleBasedColumns,
                       int llmScannedColumns, int piiColumns, int nonPiiColumns) {
            this.totalColumns = totalColumns;
            this.skippedColumns = skippedColumns;
            this.ruleBasedColumns = ruleBasedColumns;
            this.llmScannedColumns = llmScannedColumns;
            this.piiColumns = piiColumns;
            this.nonPiiColumns = nonPiiColumns;
        }

        public int getTotalColumns() { return totalColumns; }
        public void setTotalColumns(int totalColumns) { this.totalColumns = totalColumns; }

        public int getSkippedColumns() { return skippedColumns; }
        public void setSkippedColumns(int skippedColumns) { this.skippedColumns = skippedColumns; }

        public int getRuleBasedColumns() { return ruleBasedColumns; }
        public void setRuleBasedColumns(int ruleBasedColumns) { this.ruleBasedColumns = ruleBasedColumns; }

        public int getLlmScannedColumns() { return llmScannedColumns; }
        public void setLlmScannedColumns(int llmScannedColumns) { this.llmScannedColumns = llmScannedColumns; }

        public int getPiiColumns() { return piiColumns; }
        public void setPiiColumns(int piiColumns) { this.piiColumns = piiColumns; }

        public int getNonPiiColumns() { return nonPiiColumns; }
        public void setNonPiiColumns(int nonPiiColumns) { this.nonPiiColumns = nonPiiColumns; }
    }

    // =========================================================================
    // Nested: TableResult
    // =========================================================================

    public static class TableResult {

        @JsonProperty("table_name")
        private String tableName;

        @JsonProperty("pii_count")
        private int piiCount;

        private List<ColumnResult> columns;

        public TableResult() {}

        public TableResult(String tableName, int piiCount, List<ColumnResult> columns) {
            this.tableName = tableName;
            this.piiCount = piiCount;
            this.columns = columns;
        }

        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }

        public int getPiiCount() { return piiCount; }
        public void setPiiCount(int piiCount) { this.piiCount = piiCount; }

        public List<ColumnResult> getColumns() { return columns; }
        public void setColumns(List<ColumnResult> columns) { this.columns = columns; }
    }

    // =========================================================================
    // Nested: ColumnResult
    // =========================================================================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ColumnResult {

        @JsonProperty("column_id")
        private String columnId;

        @JsonProperty("column_name")
        private String columnName;

        @JsonProperty("data_type")
        private String dataType;

        @JsonProperty("top_category")
        private String topCategory;

        @JsonProperty("top_probability")
        private double topProbability;

        @JsonProperty("is_pii")
        private boolean isPii;

        @JsonProperty("scan_method")
        private String scanMethod;  // "skipped_type" | "rule_based" | "llm" | "error"

        private String error;       // only present when scan_method == "error"

        public ColumnResult() {}

        public ColumnResult(String columnId, String columnName, String dataType,
                            String topCategory, double topProbability, boolean isPii,
                            String scanMethod, String error) {
            this.columnId = columnId;
            this.columnName = columnName;
            this.dataType = dataType;
            this.topCategory = topCategory;
            this.topProbability = topProbability;
            this.isPii = isPii;
            this.scanMethod = scanMethod;
            this.error = error;
        }

        public String getColumnId() { return columnId; }
        public void setColumnId(String columnId) { this.columnId = columnId; }

        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }

        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }

        public String getTopCategory() { return topCategory; }
        public void setTopCategory(String topCategory) { this.topCategory = topCategory; }

        public double getTopProbability() { return topProbability; }
        public void setTopProbability(double topProbability) { this.topProbability = topProbability; }

        @JsonProperty("is_pii")
        public boolean isPii() { return isPii; }
        public void setPii(boolean isPii) { this.isPii = isPii; }

        public String getScanMethod() { return scanMethod; }
        public void setScanMethod(String scanMethod) { this.scanMethod = scanMethod; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
