package com.kafein.discovery.service;

import com.kafein.discovery.config.AppConfig;
import com.kafein.discovery.dto.*;
import com.kafein.discovery.entity.*;
import com.kafein.discovery.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Metadata service.
 * Handles connecting to target PostgreSQL databases, extracting schema info,
 * persisting metadata, and CRUD operations.
 *
 * Equivalent to app/metadata/service.py.
 */
@Service
public class MetadataService {

    private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";

    private final AppConfig appConfig;
    private final MetadataRecordRepository metadataRecordRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;

    public MetadataService(
            AppConfig appConfig,
            MetadataRecordRepository metadataRecordRepository,
            DbConnectionRepository dbConnectionRepository,
            TableInfoRepository tableInfoRepository,
            ColumnInfoRepository columnInfoRepository) {
        this.appConfig = appConfig;
        this.metadataRecordRepository = metadataRecordRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.tableInfoRepository = tableInfoRepository;
        this.columnInfoRepository = columnInfoRepository;
    }

    // =========================================================================
    // Encryption helpers (AES/CBC/PKCS5Padding)
    // Equivalent to Fernet encrypt/decrypt in app/metadata/service.py.
    // Key is derived from ENCRYPTION_KEY via SHA-256 to ensure 32 bytes.
    // IV is prepended to the ciphertext, then Base64-encoded.
    // =========================================================================

    private SecretKey deriveAesKey() {
        try {
            String keyStr = appConfig.getEncryptionKey();
            if (keyStr == null || keyStr.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "ENCRYPTION_KEY is not configured."
                );
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(keyStr.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive AES key", e);
        }
    }

    public String encryptPassword(String plainText) {
        try {
            SecretKey key = deriveAesKey();
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext, then Base64-encode
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    public String decryptPassword(String encryptedText) {
        try {
            SecretKey key = deriveAesKey();
            byte[] combined = Base64.getDecoder().decode(encryptedText);

            byte[] iv = Arrays.copyOfRange(combined, 0, 16);
            byte[] ciphertext = Arrays.copyOfRange(combined, 16, combined.length);

            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
            byte[] decrypted = cipher.doFinal(ciphertext);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt password: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Target DB discovery (JDBC – equivalent to psycopg2 in Python)
    // =========================================================================

    /**
     * Connect to the target PostgreSQL database via JDBC and query
     * information_schema.columns for the public schema.
     *
     * Returns a map: table_name -> list of column info maps.
     */
    private Map<String, List<Map<String, Object>>> discoverSchema(
            String host, int port, String database, String username, String password) {

        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=10",
                host, port, database);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            String sql = """
                    SELECT table_name,
                           column_name,
                           data_type,
                           ordinal_position
                    FROM   information_schema.columns
                    WHERE  table_schema = 'public'
                    ORDER  BY table_name, ordinal_position
                    """;

            Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    tables.computeIfAbsent(tableName, k -> new ArrayList<>());
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column_name", rs.getString("column_name"));
                    col.put("data_type", rs.getString("data_type"));
                    col.put("ordinal_position", rs.getInt("ordinal_position"));
                    tables.get(tableName).add(col);
                }
            }
            return tables;

        } catch (SQLException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Failed to connect to target database: " + e.getMessage()
            );
        }
    }

    // =========================================================================
    // CRUD operations
    // =========================================================================

    /**
     * Discover schema from target DB, persist everything, return structured response.
     * Equivalent to create_metadata() in app/metadata/service.py.
     */
    @Transactional
    public ConnectResponse createMetadata(ConnectRequest request) {
        // 1. Discover schema
        Map<String, List<Map<String, Object>>> tablesRaw = discoverSchema(
                request.getHost(),
                request.getPort(),
                request.getDatabase(),
                request.getUsername(),
                request.getPassword()
        );

        // 2. Create MetadataRecord
        MetadataRecord metadataRecord = new MetadataRecord();
        metadataRecord.setDatabaseName(request.getDatabase());
        metadataRecord = metadataRecordRepository.save(metadataRecord);

        // 3. Create DbConnection with encrypted password
        DbConnection dbConn = new DbConnection();
        dbConn.setMetadataId(metadataRecord.getId());
        dbConn.setHost(request.getHost());
        dbConn.setPort(String.valueOf(request.getPort()));
        dbConn.setDatabaseName(request.getDatabase());
        dbConn.setUsername(request.getUsername());
        dbConn.setEncryptedPassword(encryptPassword(request.getPassword()));
        dbConnectionRepository.save(dbConn);

        // 4. Create TableInfo + ColumnInfo records
        List<TableOut> responseTables = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tablesRaw.entrySet()) {
            String tableName = entry.getKey();
            List<Map<String, Object>> columns = entry.getValue();

            TableInfo tableInfo = new TableInfo();
            tableInfo.setMetadataId(metadataRecord.getId());
            tableInfo.setTableName(tableName);
            tableInfo.setSchemaName("public");
            tableInfo = tableInfoRepository.save(tableInfo);

            List<ColumnOut> responseColumns = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                ColumnInfo columnInfo = new ColumnInfo();
                columnInfo.setTableId(tableInfo.getId());
                columnInfo.setMetadataId(metadataRecord.getId());
                columnInfo.setColumnName((String) col.get("column_name"));
                columnInfo.setDataType((String) col.get("data_type"));
                columnInfo.setOrdinalPosition((Integer) col.get("ordinal_position"));
                columnInfo = columnInfoRepository.save(columnInfo);

                responseColumns.add(new ColumnOut(
                        columnInfo.getId().toString(),
                        columnInfo.getColumnName(),
                        columnInfo.getDataType()
                ));
            }
            responseTables.add(new TableOut(tableName, responseColumns));
        }

        return new ConnectResponse(
                metadataRecord.getId().toString(),
                request.getDatabase(),
                tablesRaw.size(),
                responseTables
        );
    }

    /**
     * Return all metadata records with table counts.
     * Equivalent to list_metadata() in app/metadata/service.py.
     */
    @Transactional(readOnly = true)
    public List<MetadataListItem> listMetadata() {
        List<MetadataRecord> records = metadataRecordRepository.findAllByOrderByCreatedAtDesc();

        // Build count map
        List<Object[]> countRows = tableInfoRepository.countByMetadataIdGrouped();
        Map<UUID, Long> countMap = countRows.stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> (Long) row[1]
                ));

        return records.stream()
                .map(r -> new MetadataListItem(
                        r.getId().toString(),
                        r.getDatabaseName(),
                        r.getCreatedAt().toString(),
                        countMap.getOrDefault(r.getId(), 0L).intValue()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Return full metadata detail including tables and columns.
     * Equivalent to get_metadata_detail() in app/metadata/service.py.
     */
    @Transactional(readOnly = true)
    public MetadataDetailResponse getMetadataDetail(String metadataId) {
        UUID uuid = parseUuid(metadataId);

        MetadataRecord record = metadataRecordRepository.findById(uuid)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Metadata record '" + metadataId + "' not found."));

        List<TableInfo> tables = tableInfoRepository.findByMetadataId(uuid);

        List<TableDetailOut> tablesOut = tables.stream()
                .map(t -> {
                    List<ColumnInfo> cols = columnInfoRepository
                            .findByTableIdOrderByOrdinalPositionAsc(t.getId());
                    List<ColumnOut> colsOut = cols.stream()
                            .map(c -> new ColumnOut(
                                    c.getId().toString(),
                                    c.getColumnName(),
                                    c.getDataType()))
                            .collect(Collectors.toList());
                    return new TableDetailOut(
                            t.getId().toString(),
                            t.getTableName(),
                            t.getSchemaName(),
                            colsOut);
                })
                .collect(Collectors.toList());

        return new MetadataDetailResponse(
                record.getId().toString(),
                record.getDatabaseName(),
                record.getCreatedAt().toString(),
                tablesOut
        );
    }

    /**
     * Delete a metadata record and all cascade-related rows.
     * Equivalent to delete_metadata() in app/metadata/service.py.
     */
    @Transactional
    public Map<String, String> deleteMetadata(String metadataId) {
        UUID uuid = parseUuid(metadataId);

        MetadataRecord record = metadataRecordRepository.findById(uuid)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Metadata record '" + metadataId + "' not found."));

        metadataRecordRepository.delete(record);

        return Map.of("status", "deleted", "metadata_id", metadataId);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid metadata_id format: '" + id + "' is not a valid UUID.");
        }
    }
}
