package com.kafein.discovery.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /classify.
 * Equivalent to the Pydantic ClassifyRequest in app/classify/router.py.
 */
public class ClassifyRequest {

    @NotBlank(message = "column_id is required")
    @JsonProperty("column_id")
    private String columnId;

    @Min(value = 1, message = "sample_count must be at least 1")
    @Max(value = 1000, message = "sample_count must be at most 1000")
    @JsonProperty("sample_count")
    private int sampleCount = 10;

    public ClassifyRequest() {}

    public String getColumnId() { return columnId; }
    public void setColumnId(String columnId) { this.columnId = columnId; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
}
