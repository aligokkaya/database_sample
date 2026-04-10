package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Response body for POST /classify.
 * Equivalent to the Pydantic ClassifyResponse in app/classify/router.py.
 */
public class ClassifyResponse {

    @JsonProperty("column_id")
    private String columnId;

    @JsonProperty("column_name")
    private String columnName;

    @JsonProperty("table_name")
    private String tableName;

    @JsonProperty("data_type")
    private String dataType;

    @JsonProperty("sample_count")
    private int sampleCount;

    @JsonProperty("top_category")
    private String topCategory;

    @JsonProperty("top_probability")
    private double topProbability;

    @JsonProperty("classifications")
    private Map<String, Double> classifications;

    public ClassifyResponse() {}

    public ClassifyResponse(String columnId, String columnName, String tableName,
                             String dataType, int sampleCount, String topCategory,
                             double topProbability, Map<String, Double> classifications) {
        this.columnId = columnId;
        this.columnName = columnName;
        this.tableName = tableName;
        this.dataType = dataType;
        this.sampleCount = sampleCount;
        this.topCategory = topCategory;
        this.topProbability = topProbability;
        this.classifications = classifications;
    }

    public String getColumnId() { return columnId; }
    public void setColumnId(String columnId) { this.columnId = columnId; }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }

    public String getTopCategory() { return topCategory; }
    public void setTopCategory(String topCategory) { this.topCategory = topCategory; }

    public double getTopProbability() { return topProbability; }
    public void setTopProbability(double topProbability) { this.topProbability = topProbability; }

    public Map<String, Double> getClassifications() { return classifications; }
    public void setClassifications(Map<String, Double> classifications) { this.classifications = classifications; }
}
