package com.kafein.discovery.controller;

import com.kafein.discovery.dto.ClassifyRequest;
import com.kafein.discovery.dto.ClassifyResponse;
import com.kafein.discovery.dto.DiscoverRequest;
import com.kafein.discovery.dto.DiscoverResponse;
import com.kafein.discovery.service.ClassifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Classification controller.
 * Exposes:
 *   POST /classify          – single column PII classification
 *   POST /classify/discover – full database PII discovery scan
 *
 * Equivalent to app/classify/router.py in the Python FastAPI project.
 */
@RestController
@Tag(name = "Classification", description = "LLM-based PII classification for database columns")
@SecurityRequirement(name = "BearerAuth")
public class ClassifyController {

    private final ClassifyService classifyService;

    public ClassifyController(ClassifyService classifyService) {
        this.classifyService = classifyService;
    }

    /**
     * POST /classify
     * Samples up to {@code sample_count} rows from the specified column, builds a prompt,
     * calls the configured LLM, and returns probability scores for 13 PII categories.
     *
     * @param request  column_id (UUID) and optional sample_count (1-1000, default 10)
     * @return structured classification result including top_category and probabilities
     */
    @Operation(
            summary = "Classify a database column for PII",
            description = "Fetches sample values from the specified column in the target database, "
                    + "submits them to the configured LLM (OpenAI-compatible API), and returns "
                    + "probability scores for all 13 supported PII categories."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Classification successful",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ClassifyResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Cannot connect to or query the target database",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized – invalid or missing token",
                    content = @Content),
            @ApiResponse(responseCode = "404",
                    description = "Column or associated connection record not found",
                    content = @Content),
            @ApiResponse(responseCode = "500",
                    description = "LLM returned an unparseable response",
                    content = @Content)
    })
    @PostMapping(value = "/classify",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ClassifyResponse classify(@Valid @RequestBody ClassifyRequest request) {
        return classifyService.classify(request);
    }

    /**
     * POST /classify/discover
     * Automatically scans ALL columns in a metadata record for PII using 3-phase filtering:
     *   Phase 1 – skip numeric/boolean types instantly (no LLM)
     *   Phase 2 – classify by column name rules (no LLM)
     *   Phase 3 – LLM only for remaining uncertain text columns
     *
     * @param request  metadata_id (UUID) and optional sample_count (1-100, default 5)
     * @return full discovery report grouped by table with per-column results
     */
    @Operation(
            summary = "Scan entire database for PII columns",
            description = "Automatically scans ALL columns across all tables in the given metadata record. "
                    + "Uses 3-phase smart filtering to minimise LLM calls: "
                    + "(1) skip numeric/boolean types instantly, "
                    + "(2) classify by column name rules without LLM, "
                    + "(3) call LLM only for uncertain text columns."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Discovery scan completed",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DiscoverResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Cannot connect to or query the target database",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized – invalid or missing token",
                    content = @Content),
            @ApiResponse(responseCode = "404",
                    description = "Metadata record or DB connection not found",
                    content = @Content),
            @ApiResponse(responseCode = "422",
                    description = "Invalid metadata_id UUID format",
                    content = @Content),
            @ApiResponse(responseCode = "500",
                    description = "Internal error during discovery scan",
                    content = @Content)
    })
    @PostMapping(value = "/classify/discover",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DiscoverResponse discoverPii(@Valid @RequestBody DiscoverRequest request) {
        return classifyService.discoverPii(request.getMetadataId(), request.getSampleCount());
    }
}
