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
 * Classification service — Java port of app/classify/service.py (Python).
 * Logic is kept identical ("birebir") to the Python implementation:
 *   - Count-based LLM system prompt
 *   - DIRECT_TYPE_MAP  (INET/CIDR → ip_address, bypasses LLM)
 *   - DIRECT_COLUMN_PATTERNS  (ordered substring match + suffix exclusion)
 *   - NEGATIVE_PII_KEYWORDS  (filter obvious non-PII before LLM)
 *   - preprocessSamples()  (flatten JSONB values)
 *   - callLlm()  (count-based + heavy recovery mapping + semantic hardening)
 *   - validateWithHeuristics()  (per-category regex/pattern checks)
 *   - executePipeline()  (unified pipeline used by both endpoints)
 */
@Service
public class ClassifyService {

    private static final Logger log = LoggerFactory.getLogger(ClassifyService.class);

    // ── PII Categories (13 total, matches Python PII_CATEGORIES) ─────────────
    static final List<String> PII_CATEGORIES = List.of(
        "email_address", "phone_number", "social_security_number", "credit_card_number",
        "national_id_number", "full_name", "first_name", "last_name", "tckn",
        "home_address", "date_of_birth", "ip_address", "not_pii"
    );

    // ── LLM System Prompt — count-based, matches Python SYSTEM_PROMPT exactly ─
    private static final String SYSTEM_PROMPT =
        "Analyze sample data for PII. You MUST return a JSON with counts.\n\n"
        + "ALLOWED KEYS: email_address, phone_number, social_security_number, credit_card_number, "
        + "tckn, national_id_number, full_name, first_name, last_name, home_address, date_of_birth, ip_address, not_pii.\n\n"
        + "GUIDELINES:\n"
        + "- Turkish TCKN (11 digits, starts non-zero) -> 'tckn'\n"
        + "- IBAN / bank account numbers -> 'credit_card_number'\n"
        + "- IP Addresses (IPv4/v6) -> 'ip_address'\n"
        + "- Masked card data (****) -> 'credit_card_number'\n"
        + "- No such PII? -> 'not_pii'\n\n"
        + "REQUIRED OUTPUT: {\"email_address\": 0, \"phone_number\": 0, ...} (All 13 keys)";

    // ── Skip Types — identical to Python SKIP_TYPES ────────────────────────────
    private static final Set<String> SKIP_TYPES = Set.of("integer", "boolean", "uuid");

    // ── Direct Type Map — PostgreSQL type → PII category, bypasses LLM ────────
    private static final Map<String, String> DIRECT_TYPE_MAP = Map.of(
        "inet", "ip_address",
        "cidr", "ip_address"
    );

    // ── Direct Column Name Patterns (ordered list, first match wins) ──────────
    // Mirrors Python DIRECT_COLUMN_PATTERNS exactly — specific patterns first.
    // Each entry: { pattern, category }
    private static final List<String[]> DIRECT_COLUMN_PATTERNS = List.of(
        // Names — more specific first
        new String[]{"full_name",       "full_name"},
        new String[]{"fullname",        "full_name"},
        new String[]{"given_name",      "first_name"},
        new String[]{"name_first",      "first_name"},
        new String[]{"first_name",      "first_name"},
        new String[]{"family_name",     "last_name"},
        new String[]{"name_last",       "last_name"},
        new String[]{"last_name",       "last_name"},
        // Email
        new String[]{"email",           "email_address"},
        // Phone / Mobile
        new String[]{"mobile",          "phone_number"},
        new String[]{"phone",           "phone_number"},
        new String[]{"telephone",       "phone_number"},
        // Address / Street
        new String[]{"street",          "home_address"},
        // Date of Birth — specific first
        new String[]{"date_of_birth",   "date_of_birth"},
        new String[]{"birth_date",      "date_of_birth"},
        new String[]{"date_born",       "date_of_birth"},
        new String[]{"_dob",            "date_of_birth"},   // holder_dob, patient_dob
        new String[]{"birth",           "date_of_birth"},   // birthdate, birthday
        new String[]{"born",            "date_of_birth"},   // date_born, born_on
        // Turkish National ID
        new String[]{"national_id",     "tckn"},
        new String[]{"id_no",           "tckn"},            // holder_id_no, patient_id_no
        new String[]{"tckn",            "tckn"},
        new String[]{"tc_no",           "tckn"},
        new String[]{"kimlik",          "tckn"},
        // IBAN / Bank → credit_card_number (closest financial category)
        new String[]{"iban",            "credit_card_number"},
        new String[]{"bank_account",    "credit_card_number"},
        // IP
        new String[]{"ip_address",      "ip_address"},
        // SSN
        new String[]{"social_security", "social_security_number"},
        new String[]{"ssn",             "social_security_number"}
    );

    // ── Non-PII Column Suffixes — these columns are NEVER PII data ────────────
    // Matches Python _NON_PII_COL_SUFFIXES exactly.
    private static final List<String> NON_PII_COL_SUFFIXES = List.of(
        "_type", "_kind", "_mode", "_status", "_flag",
        "_code", "_brand", "_model", "_category", "_class", "_label"
    );

    // ── Negative PII Keywords — skip column if matched (unless sensitive) ─────
    // Matches Python NEGATIVE_PII_KEYWORDS exactly.
    private static final Set<String> NEGATIVE_PII_KEYWORDS = Set.of(
        "pk", "fk", "_id", "created_at", "updated_at", "deleted_at", "occurred_at",
        "status", "version", "count", "amount", "price", "is_active", "is_deleted",
        "track", "log", "measure", "metric", "unit", "rating", "score", "index",
        "heart_rate", "blood_pressure", "vital_signs", "temperature",
        "user_agent", "browser", "description", "payment_type", "type", "mode", "category",
        "registration_date", "hired_at", "hire_date", "last_updated", "created_date",
        "latitude", "longitude", "geo", "postal", "zip", "quantity", "stock", "bonus", "salary"
    );

    // ── Sensitive Keywords — override negative filter when present ─────────────
    // Matches Python's is_sens logic in _execute_discovery_pipeline exactly.
    private static final List<String> SENSITIVE_KEYWORDS = List.of(
        "national", "citizen", "tax", "tckn", "social", "identity", "id_no", "kimlik",
        "iban", "policy", "dob", "birth", "email", "phone", "address"
    );

    // ── Spring beans ──────────────────────────────────────────────────────────
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
    // Public Entry Points
    // =========================================================================

    /**
     * Classify a single column by column_id.
     * Equivalent to classify_column() in app/classify/service.py.
     */
    public ClassifyResponse classify(ClassifyRequest request) {
        UUID columnUuid = parseUuid(request.getColumnId(), "column_id");

        ColumnInfo columnInfo = columnInfoRepository.findById(columnUuid)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Column '" + request.getColumnId() + "' not found."));

        TableInfo tableInfo = tableInfoRepository.findById(columnInfo.getTableId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Table record for column '" + request.getColumnId() + "' not found."));

        DbConnection dbConn = dbConnectionRepository.findByMetadataId(columnInfo.getMetadataId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No DB connection found for metadata '" + columnInfo.getMetadataId() + "'."));

        String plainPassword;
        try {
            plainPassword = metadataService.decryptPassword(dbConn.getEncryptedPassword());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to decrypt stored password: " + e.getMessage());
        }

        Map<String, Object> res = executePipeline(
            tableInfo.getTableName(), columnInfo.getColumnName(), columnInfo.getDataType(),
            dbConn, plainPassword, request.getSampleCount());

        @SuppressWarnings("unchecked")
        Map<String, Double> classifications = (Map<String, Double>) res.get("classifications");
        String topCategory  = (String) res.get("top_category");
        double topProbability = (double) res.get("top_probability");
        int sampleCount = (int) res.get("sample_count");

        return new ClassifyResponse(
            request.getColumnId(),
            columnInfo.getColumnName(),
            tableInfo.getTableName(),
            columnInfo.getDataType(),
            sampleCount,
            topCategory,
            topProbability,
            classifications);
    }

    /**
     * Full PII discovery for an entire metadata record.
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

        List<TableInfo> sortedTables = tableInfoRepository.findByMetadataIdWithColumns(metaUuid)
            .stream()
            .sorted(Comparator.comparing(TableInfo::getTableName))
            .collect(Collectors.toList());

        int totalColumns = 0, piiCount = 0;
        List<DiscoverResponse.TableResult> tablesOut = new ArrayList<>();

        for (TableInfo table : sortedTables) {
            List<DiscoverResponse.ColumnResult> colsOut = new ArrayList<>();

            List<ColumnInfo> sortedCols = table.getColumns().stream()
                .sorted(Comparator.comparing(ColumnInfo::getOrdinalPosition))
                .collect(Collectors.toList());

            for (ColumnInfo col : sortedCols) {
                totalColumns++;

                Map<String, Object> res = executePipeline(
                    table.getTableName(), col.getColumnName(), col.getDataType(),
                    dbConn, plainPassword, sampleCount);

                String topCat = (String) res.get("top_category");
                boolean isPii = !"not_pii".equals(topCat);
                if (isPii) piiCount++;

                colsOut.add(new DiscoverResponse.ColumnResult(
                    col.getId().toString(), col.getColumnName(), isPii, topCat));
            }

            int tablePii = (int) colsOut.stream()
                .filter(DiscoverResponse.ColumnResult::isPii)
                .count();
            tablesOut.add(new DiscoverResponse.TableResult(table.getTableName(), tablePii, colsOut));
        }

        return new DiscoverResponse(metadataId, record.getDatabaseName(),
            totalColumns, piiCount, tablesOut);
    }

    // =========================================================================
    // Unified Discovery Pipeline
    // =========================================================================

    /**
     * Core pipeline — equivalent to _execute_discovery_pipeline() in Python.
     *
     * Phases:
     *   0. Skip by data type  (SKIP_TYPES)
     *   1. Direct type map     (INET/CIDR → ip_address)
     *   2. Direct column name  (substring pattern match)
     *   3. Negative keyword filter (skip obvious non-PII unless sensitive)
     *   4. Fetch samples from target DB
     *   5. Pre-process samples  (flatten JSONB)
     *   6. Call LLM             (count-based)
     *   7. Validate with heuristics
     *   8. Normalize & return
     *
     * Returns map with keys: top_category, top_probability, classifications, sample_count.
     */
    private Map<String, Object> executePipeline(
            String tableName, String colName, String dtype,
            DbConnection dbConn, String password, int count) {

        String tCol = colName.toLowerCase();

        // Phase 0 – skip by data type
        if (SKIP_TYPES.contains(dtype.toLowerCase())) {
            return emptyResult();
        }

        // Phase 1 – direct type mapping (e.g. INET → ip_address)
        String directTypeCat = DIRECT_TYPE_MAP.get(dtype.toLowerCase());
        if (directTypeCat != null) {
            return directResult(directTypeCat);
        }

        // Phase 2 – direct column name pattern match
        String directColCat = directColMatch(tCol);
        if (directColCat != null) {
            log.debug("[DIRECT MATCH] {}.{} -> {}", tableName, colName, directColCat);
            return directResult(directColCat);
        }

        // Phase 3 – negative keyword filter
        boolean isSens    = SENSITIVE_KEYWORDS.stream().anyMatch(tCol::contains);
        boolean hasNeg    = NEGATIVE_PII_KEYWORDS.stream().anyMatch(tCol::contains)
                            || tCol.endsWith("_id");
        if (hasNeg && !isSens) {
            return emptyResult();
        }

        // Phase 4 – fetch sample data
        List<Object> rawSamples;
        try {
            rawSamples = fetchSampleData(
                dbConn.getHost(), Integer.parseInt(dbConn.getPort()),
                dbConn.getDatabaseName(), dbConn.getUsername(), password,
                tableName, colName, count);
        } catch (Exception e) {
            return emptyResult();
        }
        if (rawSamples.isEmpty()) return emptyResult();

        // Phase 5 – preprocess (flatten JSONB / dict values)
        List<String> samples = preprocessSamples(rawSamples);

        // Phase 6 – call LLM (count-based)
        Map<String, Double> classif = callLlm(colName, samples, tableName);

        // Phase 7 – validate with heuristics
        Map<String, Double> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : classif.entrySet()) {
            double prob = validateWithHeuristics(e.getKey(), samples, colName)
                          ? e.getValue() : 0.0;
            validated.put(e.getKey(), prob);
        }

        // Phase 8 – normalize
        double piiSum = validated.values().stream().mapToDouble(Double::doubleValue).sum();
        if (piiSum == 0) {
            // LLM returned all zeros (or heuristics rejected all) → not_pii
            Map<String, Double> res = new LinkedHashMap<>();
            for (String cat : PII_CATEGORIES) {
                if (!"not_pii".equals(cat)) res.put(cat, 0.0);
            }
            res.put("not_pii", 1.0);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("top_category",   "not_pii");
            r.put("top_probability", 1.0);
            r.put("classifications", res);
            r.put("sample_count",    rawSamples.size());
            return r;
        }

        Map<String, Double> finalMap = new LinkedHashMap<>();
        for (String cat : PII_CATEGORIES) {
            if (!"not_pii".equals(cat)) {
                double v = validated.getOrDefault(cat, 0.0);
                finalMap.put(cat, Math.round(v / piiSum * 1_000_000.0) / 1_000_000.0);
            }
        }

        String topCat = finalMap.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("not_pii");
        double topProb = finalMap.getOrDefault(topCat, 0.0);

        log.debug("[PII MATCH] {}.{} -> {}", tableName, colName, topCat);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("top_category",    topCat);
        result.put("top_probability", topProb);
        result.put("classifications", finalMap);
        result.put("sample_count",    rawSamples.size());
        return result;
    }

    // =========================================================================
    // Direct Match Helpers
    // =========================================================================

    /**
     * Equivalent to _direct_col_match() in Python.
     * Returns a PII category if the column name clearly signals a PII field,
     * or null if no pattern matches.
     */
    private String directColMatch(String colNameLower) {
        // Suffix exclusion: _type / _status / etc. are never PII data columns
        for (String suffix : NON_PII_COL_SUFFIXES) {
            if (colNameLower.endsWith(suffix)) return null;
        }
        // Ordered substring match — first hit wins
        for (String[] entry : DIRECT_COLUMN_PATTERNS) {
            if (colNameLower.contains(entry[0])) return entry[1];
        }
        return null;
    }

    /** Build an "empty / not_pii" result with 0 sample_count. */
    private Map<String, Object> emptyResult() {
        Map<String, Double> classif = new LinkedHashMap<>();
        for (String cat : PII_CATEGORIES) {
            if (!"not_pii".equals(cat)) classif.put(cat, 0.0);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("top_category",    "not_pii");
        r.put("top_probability", 1.0);
        r.put("classifications", classif);
        r.put("sample_count",    0);
        return r;
    }

    /** Build a direct-match result with probability 1.0 for the given category. */
    private Map<String, Object> directResult(String category) {
        Map<String, Double> classif = new LinkedHashMap<>();
        for (String cat : PII_CATEGORIES) {
            if (!"not_pii".equals(cat)) classif.put(cat, cat.equals(category) ? 1.0 : 0.0);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("top_category",    category);
        r.put("top_probability", 1.0);
        r.put("classifications", classif);
        r.put("sample_count",    0);
        return r;
    }

    // =========================================================================
    // Sample Fetching
    // =========================================================================

    /**
     * Fetch up to {@code sampleCount} non-null values from the target column via JDBC.
     * Equivalent to the psycopg2 fetch in _execute_discovery_pipeline() (Python).
     */
    private List<Object> fetchSampleData(
            String host, int port, String database,
            String username, String password,
            String tableName, String columnName, int sampleCount) {

        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=10",
            host, port, database);
        String sql = String.format(
            "SELECT \"%s\" FROM \"%s\" WHERE \"%s\" IS NOT NULL LIMIT %d",
            columnName.replace("\"", "\"\""),
            tableName.replace("\"", "\"\""),
            columnName.replace("\"", "\"\""),
            sampleCount);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            List<Object> results = new ArrayList<>();
            while (rs.next()) results.add(rs.getObject(1));
            return results;

        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Failed to query target database: " + e.getMessage());
        }
    }

    // =========================================================================
    // Sample Pre-processing
    // =========================================================================

    /**
     * Convert raw DB values to strings, flattening JSONB/dict values.
     * Equivalent to _preprocess_samples() in Python.
     *
     * JSONB columns arrive as a JSON string from JDBC; we parse them and
     * flatten to "key: value | key: value" so the LLM can see field names.
     */
    @SuppressWarnings("unchecked")
    private List<String> preprocessSamples(List<Object> raw) {
        List<String> result = new ArrayList<>();
        for (Object r : raw) {
            if (r instanceof Map) {
                // Already a map (some JDBC drivers deserialize JSONB)
                Map<?, ?> m = (Map<?, ?>) r;
                result.add(m.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(" | ")));
            } else {
                String s = (r == null) ? "null" : r.toString();
                if (s.trim().startsWith("{")) {
                    try {
                        Map<String, Object> obj = objectMapper.readValue(s,
                            new TypeReference<Map<String, Object>>() {});
                        result.add(obj.entrySet().stream()
                            .map(e -> e.getKey() + ": " + e.getValue())
                            .collect(Collectors.joining(" | ")));
                        continue;
                    } catch (Exception ignored) {
                        // not valid JSON — fall through to plain string
                    }
                }
                result.add(s);
            }
        }
        return result;
    }

    // =========================================================================
    // LLM Call
    // =========================================================================

    /**
     * Count-based LLM call with heavy recovery mapping, semantic hardening,
     * and national_id_number → tckn consolidation.
     * Equivalent to _call_llm() in Python.
     *
     * Returns a map of { category → normalized probability } for all non-not_pii
     * categories.  If the LLM returns nothing useful, all values are 0.0.
     */
    private Map<String, Double> callLlm(String columnName, List<String> samples, String tableName) {
        log.debug("[LLM CALL] {}.{}", tableName, columnName);

        // Cap at 15 samples (matches Python [:15])
        List<String> capped = samples.size() > 15 ? samples.subList(0, 15) : samples;

        StringBuilder sb = new StringBuilder();
        for (String v : capped) sb.append("- ").append(v).append("\n");

        String userMsg = "Table: " + tableName + "\nColumn: " + columnName
            + "\nSamples:\n" + sb;

        Map<String, Object> systemMsg = Map.of("role", "system", "content", SYSTEM_PROMPT);
        Map<String, Object> userMsgMap = Map.of("role", "user",  "content", userMsg);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       appConfig.getLlmModel());
        body.put("messages",    List.of(systemMsg, userMsgMap));
        body.put("temperature", 0.0);

        // response_format only for official OpenAI endpoints (not Ollama)
        String baseUrl = appConfig.getLlmBaseUrl();
        if (baseUrl != null && baseUrl.contains("openai.com")) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(appConfig.getLlmApiKey());

        String endpointUrl = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> rawData;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> apiResponse = restTemplate.postForObject(
                endpointUrl, new HttpEntity<>(body, headers), Map.class);
            String rawText = stripThinkTags(extractContent(apiResponse));
            rawData = extractJsonRaw(rawText);
        } catch (Exception e) {
            log.debug("[LLM ERR] {}", e.getMessage());
            return emptyClassifications();
        }

        log.debug("[LLM RAW] {}: {}", columnName, rawData);

        // ── Heavy Duty Recovery Mapping ──────────────────────────────────────
        // Start with 0 counts for every non-not_pii category.
        Map<String, Integer> cleaned = new LinkedHashMap<>();
        for (String cat : PII_CATEGORIES) {
            if (!"not_pii".equals(cat)) cleaned.put(cat, 0);
        }

        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            String kLower = entry.getKey().toLowerCase();
            int val;
            try {
                val = (int) Math.round(((Number) entry.getValue()).doubleValue());
            } catch (Exception e) {
                continue;
            }
            if (val <= 0) continue;

            if (cleaned.containsKey(kLower)) {
                cleaned.merge(kLower, val, Integer::sum);
            } else if (kLower.contains("card")   || kLower.contains("cc")) {
                cleaned.merge("credit_card_number", val, Integer::sum);
            } else if (kLower.contains("iban")   || kLower.contains("bank") || kLower.contains("acc")) {
                cleaned.merge("credit_card_number", val, Integer::sum);
            } else if (kLower.contains("tckn")   || kLower.contains("tc")
                    || kLower.contains("national") || kLower.contains("citizen")
                    || kLower.contains("identity") || kLower.contains("kimlik")) {
                cleaned.merge("tckn", val, Integer::sum);
            } else if (kLower.contains("ip")     || kLower.contains("host")) {
                cleaned.merge("ip_address", val, Integer::sum);
            } else if (kLower.contains("phone")  || kLower.contains("tel")) {
                cleaned.merge("phone_number", val, Integer::sum);
            } else if (kLower.contains("birth")  || kLower.contains("dob") || kLower.contains("dogum")) {
                cleaned.merge("date_of_birth", val, Integer::sum);
            } else if (kLower.contains("email")  || kLower.contains("e-mail")) {
                cleaned.merge("email_address", val, Integer::sum);
            } else if (kLower.contains("addres") || kLower.contains("adres")) {
                cleaned.merge("home_address", val, Integer::sum);
            } else if (kLower.contains("ssn")    || kLower.contains("social")) {
                cleaned.merge("social_security_number", val, Integer::sum);
            }
            // "not_pii" values are intentionally ignored — only PII counts matter
        }

        // ── Semantic Hardening for Names ─────────────────────────────────────
        // If column clearly names a first/last component, move full_name votes there.
        String cLower = columnName.toLowerCase();
        if (cLower.contains("first") || cLower.contains("ad") || cLower.contains("adi")) {
            int fnVal = cleaned.getOrDefault("full_name", 0);
            if (fnVal > 0) {
                cleaned.merge("first_name", fnVal, Integer::sum);
                cleaned.put("full_name", 0);
            }
        } else if (cLower.contains("last") || cLower.contains("soy") || cLower.contains("surname")) {
            int fnVal = cleaned.getOrDefault("full_name", 0);
            if (fnVal > 0) {
                cleaned.merge("last_name", fnVal, Integer::sum);
                cleaned.put("full_name", 0);
            }
        }

        // ── Consolidation: national_id_number → tckn ─────────────────────────
        int natVal = cleaned.getOrDefault("national_id_number", 0);
        cleaned.merge("tckn", natVal, Integer::sum);
        cleaned.put("national_id_number", 0);

        int total = cleaned.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return emptyClassifications();

        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : cleaned.entrySet()) {
            result.put(e.getKey(),
                Math.round((double) e.getValue() / total * 1_000_000.0) / 1_000_000.0);
        }
        return result;
    }

    /** All non-not_pii categories at 0.0. Used when LLM call fails or returns nothing. */
    private Map<String, Double> emptyClassifications() {
        Map<String, Double> r = new LinkedHashMap<>();
        for (String cat : PII_CATEGORIES) {
            if (!"not_pii".equals(cat)) r.put(cat, 0.0);
        }
        return r;
    }

    // =========================================================================
    // Heuristic Validation
    // =========================================================================

    /**
     * Per-category data validation. Rejects an LLM decision when the sample data
     * clearly does not match the claimed category.
     * Equivalent to _validate_with_heuristics() in Python.
     */
    private boolean validateWithHeuristics(String category, List<String> samples, String columnName) {
        if (samples.isEmpty()) return true;
        String blob = String.join(" ", samples).toLowerCase();
        String tCol = columnName.toLowerCase();

        switch (category) {
            case "email_address":
                return blob.contains("@") && blob.contains(".");

            case "ip_address":
                return Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F:]{5,}")
                              .matcher(blob).find();

            case "tckn":
                // 11-digit number, first digit non-zero
                return Pattern.compile("[1-9]\\d{10}")
                              .matcher(blob.replace(" ", "")).find();

            case "credit_card_number":
                // Masked card (****) or at least 13 consecutive digits
                return blob.contains("*") || countDigits(blob) >= 13;

            case "date_of_birth": {
                List<String> rejects = List.of(
                    "created", "updated", "hire", "registration", "order", "login");
                boolean hasReject = rejects.stream().anyMatch(tCol::contains);
                return !hasReject && blob.chars().anyMatch(Character::isDigit);
            }

            case "phone_number": {
                String stripped = blob.replace(" ", "").replace("-", "");
                return Pattern.compile("\\+?\\d{9,}").matcher(stripped).find();
            }

            case "first_name":
            case "last_name":
            case "full_name": {
                // Must contain some alphabetic content (including Turkish letters)
                String clean = blob.replaceAll("[^a-zA-ZğüşöçİĞÜŞÖÇ ]", "");
                return clean.trim().length() > 2;
            }

            default:
                return true;
        }
    }

    private int countDigits(String s) {
        int count = 0;
        for (char c : s.toCharArray()) if (Character.isDigit(c)) count++;
        return count;
    }

    // =========================================================================
    // Response Parsing Helpers
    // =========================================================================

    /** Extract the assistant message content from the OpenAI-compatible response envelope. */
    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> apiResponse) {
        if (apiResponse == null) throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Empty response from LLM API");

        List<Map<String, Object>> choices =
            (List<Map<String, Object>>) apiResponse.get("choices");
        if (choices == null || choices.isEmpty()) throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "LLM API returned no choices");

        Map<String, Object> message =
            (Map<String, Object>) choices.get(0).get("message");
        if (message == null) throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "LLM API choice missing message");

        Object content = message.get("content");
        if (content == null) throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "LLM API message has null content");

        return content.toString().trim();
    }

    /**
     * Strip {@code <think>...</think>} blocks emitted by some reasoning models
     * (e.g. DeepSeek-R1, Qwen QwQ) before the actual JSON response.
     */
    private String stripThinkTags(String text) {
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        return cleaned.isEmpty() ? text : cleaned;
    }

    /**
     * Robustly extract a JSON object from LLM text.
     * Returns a raw {@code Map<String, Object>} so values may be Integer or Double
     * (the count-based prompt returns integers; probability-style responses return doubles).
     * Equivalent to _extract_json() in Python.
     */
    private Map<String, Object> extractJsonRaw(String text) {
        TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {};

        // 1. Direct parse
        try { return objectMapper.readValue(text, typeRef); }
        catch (Exception ignored) {}

        // 2. Markdown fence: ```json { ... } ```  or  ``` { ... } ```
        Pattern fencePattern = Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
        Matcher fenceMatcher = fencePattern.matcher(text);
        if (fenceMatcher.find()) {
            try { return objectMapper.readValue(fenceMatcher.group(1), typeRef); }
            catch (Exception ignored) {}
        }

        // 3. First balanced { … } block
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try { return objectMapper.readValue(text.substring(start, end + 1), typeRef); }
            catch (Exception ignored) {}
        }

        return Map.of(); // give up — recovery mapping will handle empty result
    }

    // =========================================================================
    // UUID Helper
    // =========================================================================

    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid " + fieldName + " format: '" + value + "' is not a valid UUID.");
        }
    }
}
