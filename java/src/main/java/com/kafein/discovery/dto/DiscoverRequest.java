package com.kafein.discovery.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /classify/discover.
 * Equivalent to DiscoverRequest in app/classify/router.py.
 */
public class DiscoverRequest {

    @NotBlank(message = "metadata_id must not be blank")
    private String metadataId;

    @Min(value = 1, message = "sample_count must be at least 1")
    @Max(value = 100, message = "sample_count must be at most 100")
    private int sampleCount = 5;

    // ---------- Constructors ----------

    public DiscoverRequest() {}

    public DiscoverRequest(String metadataId, int sampleCount) {
        this.metadataId = metadataId;
        this.sampleCount = sampleCount;
    }

    // ---------- Getters & Setters ----------

    public String getMetadataId() { return metadataId; }
    public void setMetadataId(String metadataId) { this.metadataId = metadataId; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
}
