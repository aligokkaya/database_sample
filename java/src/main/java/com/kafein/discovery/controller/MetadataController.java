package com.kafein.discovery.controller;

import com.kafein.discovery.dto.ConnectRequest;
import com.kafein.discovery.dto.ConnectResponse;
import com.kafein.discovery.dto.MetadataDetailResponse;
import com.kafein.discovery.dto.MetadataListItem;
import com.kafein.discovery.service.MetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Metadata controller.
 * Handles database discovery (connect + introspect) and CRUD on stored metadata.
 *
 * Equivalent to app/metadata/router.py in the Python FastAPI project.
 *
 * Endpoints:
 *   POST   /db/metadata        – connect to a target DB and harvest schema
 *   GET    /metadata           – list all saved metadata records
 *   GET    /metadata/{id}      – detailed view of a single record
 *   DELETE /metadata/{id}      – remove a record and all related data
 */
@RestController
@Tag(name = "Metadata", description = "Database schema discovery and metadata management")
@SecurityRequirement(name = "BearerAuth")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    // -------------------------------------------------------------------------
    // POST /db/metadata
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Connect to a database and harvest schema metadata",
            description = "Connects to the specified PostgreSQL database via JDBC, introspects the "
                    + "public schema, persists the results, and returns the discovered structure. "
                    + "The connection password is stored AES-encrypted."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Metadata created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ConnectResponse.class))),
            @ApiResponse(responseCode = "400", description = "Cannot connect to target database",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized – invalid or missing token",
                    content = @Content)
    })
    @PostMapping(value = "/db/metadata",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectResponse createMetadata(@Valid @RequestBody ConnectRequest request) {
        return metadataService.createMetadata(request);
    }

    // -------------------------------------------------------------------------
    // GET /metadata
    // -------------------------------------------------------------------------

    @Operation(
            summary = "List all metadata records",
            description = "Returns a summary list of all previously harvested database metadata, "
                    + "ordered by creation date descending."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List returned successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content)
    })
    @GetMapping(value = "/metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MetadataListItem> listMetadata() {
        return metadataService.listMetadata();
    }

    // -------------------------------------------------------------------------
    // GET /metadata/{id}
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Get metadata record detail",
            description = "Returns the full details of a single metadata record, including all "
                    + "discovered tables and their columns."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Record found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MetadataDetailResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Record not found",
                    content = @Content)
    })
    @GetMapping(value = "/metadata/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public MetadataDetailResponse getMetadata(
            @Parameter(description = "UUID of the metadata record")
            @PathVariable("id") String id) {
        return metadataService.getMetadataDetail(id);
    }

    // -------------------------------------------------------------------------
    // DELETE /metadata/{id}
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Delete a metadata record",
            description = "Permanently deletes a metadata record together with all associated "
                    + "connection details, tables, and column information (cascade)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Record deleted successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Record not found",
                    content = @Content)
    })
    @DeleteMapping(value = "/metadata/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> deleteMetadata(
            @Parameter(description = "UUID of the metadata record to delete")
            @PathVariable("id") String id) {
        return metadataService.deleteMetadata(id);
    }
}
