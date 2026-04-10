package com.kafein.discovery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafein.discovery.config.AppConfig;
import com.kafein.discovery.dto.ClassifyRequest;
import com.kafein.discovery.dto.ClassifyResponse;
import com.kafein.discovery.dto.DiscoverResponse;
import com.kafein.discovery.entity.ColumnInfo;
import com.kafein.discovery.entity.DbConnection;
import com.kafein.discovery.entity.MetadataRecord;
import com.kafein.discovery.entity.TableInfo;
import com.kafein.discovery.repository.ColumnInfoRepository;
import com.kafein.discovery.repository.DbConnectionRepository;
import com.kafein.discovery.repository.MetadataRecordRepository;
import com.kafein.discovery.repository.TableInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Classification service.
 * Handles:
 *   1. Resolving column → table → connection records
 *   2. Fetching sample data from the target database via JDBC
 *   3. Building the LLM prompt and calling the OpenAI-compatible chat completions API
 *   4. Parsing and normalising the JSON probability response
 *
 * Equivalent to app/classify/service.py in the Python FastAPI project.
 */
@Service
public class ClassifyService {

    private static final Logger log = LoggerFactory.getLogger(ClassifyService.class);

    // -------------------------------------------------------------------------
    // All 13 supported PII categories (must match the LLM system prompt)
    // -------------------------------------------------------------------------
    static final List<String> PII_CATEGORIES = List.of(
            "email_address",
            "phone_number",
            "social_security_number",
            "credit_card_number",
            "national_id_number",
            "full_name",
            "first_name",
            "last_name",
            "tckn",
            "home_address",
            "date_of_birth",
            "ip_address",
            "not_pii"
    );

    /**
     * System prompt sent to the LLM on every classification request.
     * The /no_think prefix suppresses chain-of-thought reasoning on models that
     * support it (e.g. DeepSeek-R1, Qwen QwQ).
     */
    private static final String SYSTEM_PROMPT =
            "/no_think\n"
            + "You are a data privacy expert specialising in PII (Personally Identifiable Information) detection.\n\n"
            + "Your task is to analyse a list of sample values from a single database column and determine "
            + "the probability that the column belongs to each of the following 13 categories:\n\n"
            + "1. email_address     – Email addresses (e.g., user@example.com)\n"
            + "2. phone_number      – Phone numbers in any format (e.g., +1-555-123-4567, 05551234567)\n"
            + "3. social_security_number – US Social Security Numbers (e.g., 123-45-6789)\n"
            + "4. credit_card_number – Credit/debit card numbers (e.g., 4111111111111111, 4111-1111-1111-1111)\n"
            + "5. national_id_number – National ID numbers from any country (non-Turkish)\n"
            + "6. full_name         – Full names (first + last, e.g., John Smith, Jane Doe)\n"
            + "7. first_name        – First/given names only (e.g., John, Mary)\n"
            + "8. last_name         – Last/family names only (e.g., Smith, Johnson)\n"
            + "9. tckn              – Turkish Citizenship Number (T.C. Kimlik No): exactly 11 digits, first digit non-zero\n"
            + "10. home_address     – Physical addresses (street, city, postal code, etc.)\n"
            + "11. date_of_birth    – Dates of birth in any format (e.g., 1990-05-15, 15/05/1990)\n"
            + "12. ip_address       – IPv4 or IPv6 addresses (e.g., 192.168.1.1, 2001:db8::1)\n"
            + "13. not_pii          – Data that does not match any PII category (e.g., product codes, prices, counts)\n\n"
            + "Rules:\n"
            + "- Probabilities MUST sum to exactly 1.0.\n"
            + "- Each probability is a float between 0.0 and 1.0.\n"
            + "- Return ONLY a valid JSON object with all 13 keys listed above.\n"
            + "- Do not include any explanation outside the JSON.\n"
            + "- Consider the column name as a strong hint, but base the classification primarily on the actual sample values.\n"
            + "- If the data is clearly not any type of PII, assign most probability to \"not_pii\".\n\n"
            + "Example response format:\n"
            + "{\n"
            + "  \"email_address\": 0.95,\n"
            + "  \"phone_number\": 0.01,\n"
            + "  \"social_security_number\": 0.0,\n"
            + "  \"credit_card_number\": 0.0,\n"
            + "  \"national_id_number\": 0.0,\n"
            + "  \"full_name\": 0.0,\n"
            + "  \"first_name\": 0.0,\n"
            + "  \"last_name\": 0.0,\n"
            + "  \"tckn\": 0.0,\n"
            + "  \"home_address\": 0.0,\n"
            + "  \"date_of_birth\": 0.0,\n"
            + "  \"ip_address\": 0.0,\n"
            + "  \"not_pii\": 0.04\n"
            + "}";

    // ── Smart discovery constants ──────────────────────────────────────────────

    private static final Set<String> SKIP_TYPES = Set.of(
        "integer", "bigint", "smallint", "int", "int2", "int4", "int8",
        "serial", "bigserial", "boolean", "bool",
        "numeric", "decimal", "real", "double precision", "float4", "float8",
        "uuid", "jsonb", "json", "bytea", "oid"
    );

    private static final List<Map.Entry<String, List<String>>> PII_NAME_RULES = List.of(
        Map.entry("email_address",          List.of("email", "mail")),
        Map.entry("phone_number",           List.of("phone", "tel", "gsm", "mobile", "cellular")),
        Map.entry("tckn",                   List.of("tckn", "tc_kimlik", "tc_no", "kimlik_no")),
        Map.entry("social_security_number", List.of("ssn", "social_security")),
        Map.entry("credit_card_number",     List.of("credit_card", "card_number", "card_no")),
        Map.entry("ip_address",             List.of("ip_address", "ip_addr", "ipaddress")),
        Map.entry("full_name",              List.of("full_name", "fullname")),
        Map.entry("first_name",             List.of("first_name", "firstname", "given_name")),
        Map.entry("last_name",              List.of("last_name", "lastname", "surname", "family_name", "soyad")),
        Map.entry("home_address",           List.of("street_address", "home_address", "address")),
        Map.entry("date_of_birth",          List.of("date_of_birth", "birth_date", "dob", "birthday")),
        Map.entry("national_id_number",     List.of("national_id", "passport", "driver_license"))
    );

    private static final List<String> NOT_PII_KEYWORDS = List.of(
        "_id", "count", "amount", "price", "cost", "total",
        "quantity", "status", "code", "type", "rating",
        "percentage", "level", "stock", "weight", "score"
    );

    private final AppConfig appConfig;
    private final ColumnInfoRepository columnInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final MetadataRecordRepository metadataRecordRepository;
    private final MetadataService metadataService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ClassifyService(
            AppConfig appConfig,
            ColumnInfoRepository columnInfoRepository,
            TableInfoRepository tableInfoRepository,
            DbConnectionRepository dbConnectionRepository,
            MetadataRecordRepository metadataRecordRepository,
            MetadataService metadataService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.columnInfoRepository = columnInfoRepository;
        this.tableInfoRepository = tableInfoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.metadataRecordRepository = metadataRecordRepository;
        this.metadataService = metadataService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Main classification workflow:
     * 1. Resolve column → table → connection
     * 2. Fetch sample data from the target DB
     * 3. Call LLM for classification
     * 4. Return structured result
     *
     * Equivalent to classify_column() in app/classify/service.py.
     */
    public ClassifyResponse classify(ClassifyRequest request) {
        UUID columnUuid = parseUuid(request.getColumnId(), "column_id");

        // 1. Resolve ColumnInfo
        ColumnInfo columnInfo = columnInfoRepository.findById(columnUuid)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Column '" + request.getColumnId() + "' not found."));

        // 2. Resolve TableInfo
        TableInfo tableInfo = tableInfoRepository.findById(columnInfo.getTableId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Table record for column '" + request.getColumnId() + "' not found."));

        // 3. Resolve DbConnection
        DbConnection dbConn = dbConnectionRepository
                .findByMetadataId(columnInfo.getMetadataId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No DB connection found for metadata '" + columnInfo.getMetadataId() + "'."));

        // 4. Decrypt password
        String plainPassword;
        try {
            plainPassword = metadataService.decryptPassword(dbConn.getEncryptedPassword());
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to decrypt stored password: " + e.getMessage());
        }

        // 5. Fetch sample data
        List<Object> samples = fetchSampleData(
                dbConn.getHost(),
                Integer.parseInt(dbConn.getPort()),
                dbConn.getDatabaseName(),
                dbConn.getUsername(),
                plainPassword,
                tableInfo.getTableName(),
                columnInfo.getColumnName(),
                request.getSampleCount()
        );

        // 6. Call LLM
        Map<String, Double> classifications = callLlm(columnInfo.getColumnName(), samples);

        // 7. Determine top category
        String topCategory = classifications.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("not_pii");

        double topProbability = classifications.getOrDefault(topCategory, 0.0);

        return new ClassifyResponse(
                request.getColumnId(),
                columnInfo.getColumnName(),
                tableInfo.getTableName(),
                columnInfo.getDataType(),
                samples.size(),
                topCategory,
                topProbability,
                classifications
        );
    }

    // =========================================================================
    // Target DB sampling
    // =========================================================================

    /**
     * Fetch up to {@code sampleCount} distinct non-null values from the specified column.
     * Uses JDBC with double-quoted identifiers to handle reserved words and mixed-case names.
     *
     * Equivalent to _fetch_sample_data() in app/classify/service.py.
     */
    private List<Object> fetchSampleData(
            String host, int port, String database,
            String username, String password,
            String tableName, String columnName, int sampleCount) {

        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=10",
                host, port, database);

        // Use double-quoted identifiers to safely handle reserved words / mixed case
        String sql = String.format(
                "SELECT \"%s\" FROM \"%s\" WHERE \"%s\" IS NOT NULL LIMIT %d",
                columnName.replace("\"", "\"\""),
                tableName.replace("\"", "\"\""),
                columnName.replace("\"", "\"\""),
                sampleCount
        );

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            List<Object> results = new ArrayList<>();
            while (rs.next()) {
                results.add(rs.getObject(1));
            }
            return results;

        } catch (SQLException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failed to query target database: " + e.getMessage());
        }
    }

    // =========================================================================
    // LLM call and response parsing
    // =========================================================================

    /**
     * Build the OpenAI chat completions request, call the API via RestTemplate,
     * parse the JSON response, and normalise probabilities so they sum to 1.0.
     *
     * Equivalent to _call_llm() in app/classify/service.py.
     */
    private Map<String, Double> callLlm(String columnName, List<Object> sampleValues) {
        // Cap at 50 samples for token safety (mirrors Python implementation)
        List<Object> capped = sampleValues.size() > 50
                ? sampleValues.subList(0, 50)
                : sampleValues;

        StringBuilder sb = new StringBuilder();
        for (Object v : capped) {
            sb.append("  - ").append(v == null ? "null" : v.toString()).append("\n");
        }

        String userMessage =
                "Column name: " + columnName + "\n\n"
                + "Sample values (" + sampleValues.size() + " rows):\n" + sb
                + "\nClassify this column according to the 13 PII categories described in the "
                + "system prompt. Return ONLY a valid JSON object with all 13 keys, no extra text.";

        // Build request payload
        Map<String, Object> systemMsg = Map.of("role", "system", "content", SYSTEM_PROMPT);
        Map<String, Object> userMsg   = Map.of("role", "user",   "content", userMessage);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", appConfig.getLlmModel());
        body.put("messages", List.of(systemMsg, userMsg));
        body.put("temperature", 0.0);

        // Add response_format only for official OpenAI endpoints (not local Ollama etc.)
        String baseUrl = appConfig.getLlmBaseUrl();
        if (baseUrl != null && baseUrl.contains("openai.com")) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(appConfig.getLlmApiKey());

        String endpointUrl = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        String rawResponse;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> apiResponse = restTemplate.postForObject(
                    endpointUrl,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            rawResponse = extractContent(apiResponse);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "LLM API call failed: " + e.getMessage());
        }

        // Strip <think>…</think> tags that some models (e.g. DeepSeek-R1) emit before JSON
        rawResponse = stripThinkTags(rawResponse);

        Map<String, Double> parsed;
        try {
            parsed = extractJson(rawResponse);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "LLM returned invalid JSON: " + e.getMessage());
        }

        // Ensure all categories are present and values are doubles
        Map<String, Double> result = new LinkedHashMap<>();
        for (String cat : PII_CATEGORIES) {
            result.put(cat, parsed.getOrDefault(cat, 0.0));
        }

        // Normalise so probabilities sum to 1.0 (handle floating-point drift)
        double total = result.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total > 0) {
            result.replaceAll((k, v) -> Math.round(v / total * 1_000_000.0) / 1_000_000.0);
        } else {
            // Fallback – mark as not_pii when LLM returns all zeros
            result.replaceAll((k, v) -> 0.0);
            result.put("not_pii", 1.0);
        }

        return result;
    }

    /**
     * Navigate the OpenAI-compatible response envelope to extract the assistant message content.
     */
    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> apiResponse) {
        if (apiResponse == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Empty response from LLM API");
        }
        List<Map<String, Object>> choices =
                (List<Map<String, Object>>) apiResponse.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "LLM API returned no choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "LLM API choice missing message");
        }
        Object content = message.get("content");
        if (content == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "LLM API message has null content");
        }
        return content.toString().trim();
    }

    /**
     * Remove {@code <think>...</think>} blocks that some reasoning models prepend before
     * their actual response.  Also trims leading/trailing whitespace.
     */
    private String stripThinkTags(String text) {
        // Remove <think>...</think> blocks (greedy across newlines)
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        return cleaned.isEmpty() ? text : cleaned;
    }

    /**
     * Robustly extract a JSON object from the LLM text response.
     * Tries three strategies in order:
     *   1. Direct JSON parse
     *   2. Extract from ```json ... ``` or ``` ... ``` markdown fences
     *   3. Find the first '{' … '}' block in the text
     *
     * Equivalent to _extract_json() in app/classify/service.py.
     */
    private Map<String, Double> extractJson(String text) throws Exception {
        TypeReference<Map<String, Double>> typeRef = new TypeReference<>() {};

        // 1. Direct parse
        try {
            return objectMapper.readValue(text, typeRef);
        } catch (Exception ignored) {
            // fall through
        }

        // 2. Markdown code fence: ```json { ... } ```  or  ``` { ... } ```
        Pattern fencePattern = Pattern.compile(
                "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
        Matcher fenceMatcher = fencePattern.matcher(text);
        if (fenceMatcher.find()) {
            try {
                return objectMapper.readValue(fenceMatcher.group(1), typeRef);
            } catch (Exception ignored) {
                // fall through
            }
        }

        // 3. Find first balanced { ... } block
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return objectMapper.readValue(text.substring(start, end + 1), typeRef);
            } catch (Exception ignored) {
                // fall through
            }
        }

        throw new IllegalArgumentException(
                "No valid JSON object found in LLM response: "
                + text.substring(0, Math.min(200, text.length())));
    }

    // =========================================================================
    // Smart discovery
    // =========================================================================

    /**
     * Rule-based column name classifier.
     * Returns a PII category name, "not_pii", or null (uncertain → LLM).
     */
    private String ruleBasedClassify(String columnName) {
        String lower = columnName.toLowerCase();

        for (Map.Entry<String, List<String>> entry : PII_NAME_RULES) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }

        for (String kw : NOT_PII_KEYWORDS) {
            if (lower.contains(kw)) {
                return "not_pii";
            }
        }

        return null; // uncertain → send to LLM
    }

    /**
     * Full PII discovery for an entire metadata record.
     * Uses 3-phase smart filtering to minimise LLM calls:
     *   Phase 1 – skip numeric/boolean types (instant)
     *   Phase 2 – classify by column name rules (instant)
     *   Phase 3 – LLM for remaining uncertain text columns
     *
     * Equivalent to discover_metadata() in app/classify/service.py.
     */
    public DiscoverResponse discoverPii(String metadataId, int sampleCount) {
        UUID metaUuid;
        try {
            metaUuid = UUID.fromString(metadataId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Invalid metadata_id: " + metadataId);
        }

        MetadataRecord record = metadataRecordRepository.findById(metaUuid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Metadata '" + metadataId + "' not found."));

        DbConnection dbConn = dbConnectionRepository.findByMetadataId(metaUuid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No DB connection for metadata '" + metadataId + "'."));

        String plainPassword;
        try {
            plainPassword = metadataService.decryptPassword(dbConn.getEncryptedPassword());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to decrypt password: " + e.getMessage());
        }

        int totalColumns = 0, skipped = 0, ruleBased = 0, llmScanned = 0, piiCount = 0;
        List<DiscoverResponse.TableResult> tablesOut = new ArrayList<>();

        // Load tables with columns in a single query (avoids MultipleBagFetchException)
        List<TableInfo> sortedTables = tableInfoRepository.findByMetadataIdWithColumns(metaUuid)
            .stream()
            .sorted(Comparator.comparing(TableInfo::getTableName))
            .collect(Collectors.toList());

        for (TableInfo table : sortedTables) {
            List<DiscoverResponse.ColumnResult> colsOut = new ArrayList<>();

            List<ColumnInfo> sortedCols = table.getColumns().stream()
                .sorted(Comparator.comparing(ColumnInfo::getOrdinalPosition))
                .collect(Collectors.toList());

            for (ColumnInfo col : sortedCols) {
                totalColumns++;
                String dtype = col.getDataType().toLowerCase();

                // Phase 1 – skip by data type
                if (SKIP_TYPES.contains(dtype)) {
                    skipped++;
                    colsOut.add(new DiscoverResponse.ColumnResult(
                        col.getId().toString(), col.getColumnName(), col.getDataType(),
                        "not_pii", 1.0, false, "skipped_type", null));
                    continue;
                }

                // Phase 2 – rule-based
                String ruleResult = ruleBasedClassify(col.getColumnName());
                if (ruleResult != null) {
                    ruleBased++;
                    boolean isPii = !ruleResult.equals("not_pii");
                    if (isPii) piiCount++;
                    colsOut.add(new DiscoverResponse.ColumnResult(
                        col.getId().toString(), col.getColumnName(), col.getDataType(),
                        ruleResult, 0.95, isPii, "rule_based", null));
                    continue;
                }

                // Phase 3 – LLM
                try {
                    List<Object> samples = fetchSampleData(
                        dbConn.getHost(),
                        Integer.parseInt(dbConn.getPort()),
                        dbConn.getDatabaseName(),
                        dbConn.getUsername(),
                        plainPassword,
                        table.getTableName(),
                        col.getColumnName(),
                        sampleCount);

                    Map<String, Double> classifications = callLlm(col.getColumnName(), samples);
                    String topCat = classifications.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("not_pii");
                    double topProb = classifications.getOrDefault(topCat, 0.0);
                    boolean isPii = !topCat.equals("not_pii");

                    llmScanned++;
                    if (isPii) piiCount++;
                    colsOut.add(new DiscoverResponse.ColumnResult(
                        col.getId().toString(), col.getColumnName(), col.getDataType(),
                        topCat, topProb, isPii, "llm", null));

                } catch (Exception e) {
                    colsOut.add(new DiscoverResponse.ColumnResult(
                        col.getId().toString(), col.getColumnName(), col.getDataType(),
                        "unknown", 0.0, false, "error", e.getMessage()));
                }
            }

            int tablePii = (int) colsOut.stream()
                .filter(DiscoverResponse.ColumnResult::isPii)
                .count();
            tablesOut.add(new DiscoverResponse.TableResult(
                table.getTableName(), tablePii, colsOut));
        }

        DiscoverResponse.Summary summary = new DiscoverResponse.Summary(
            totalColumns, skipped, ruleBased, llmScanned, piiCount, totalColumns - piiCount);

        return new DiscoverResponse(metadataId, record.getDatabaseName(),
            sampleCount, summary, tablesOut);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid " + fieldName + " format: '" + value + "' is not a valid UUID.");
        }
    }
}
