package com.pibase.pibase_api.service;

import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.dto.response.QueryResultResponse;
import com.pibase.pibase_api.entity.DatabaseEngine;
import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.entity.DbStatus;
import com.pibase.pibase_api.exception.PlaygroundException;
import com.pibase.pibase_api.exception.ResourceNotFoundException;
import com.pibase.pibase_api.repository.DatabaseInstanceRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlaygroundService {

    private final DatabaseInstanceRepository dbRepository;
    private final PiBaseProperties piBaseProperties;

    private final ConcurrentHashMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    private static final int MAX_ROWS = 1000;
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("\\bCOPY\\b.*\\bFROM\\b.*\\bPROGRAM\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blo_import\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blo_export\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpg_read_file\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpg_ls_dir\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bLOAD_FILE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bINTO\\s+OUTFILE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bINTO\\s+DUMPFILE\\b", Pattern.CASE_INSENSITIVE)
    );

    public QueryResultResponse executeQuery(String userId, String sql) {
        // 1. validate
        validateSql(sql);

        // 2. get running db
        DatabaseInstance db = dbRepository.findByUserIdAndStatusIn(userId, List.of(DbStatus.RUNNING))
                .orElseThrow(() -> new ResourceNotFoundException("No running database"));

        // 3. get connection pool
        HikariDataSource ds = getOrCreatePool(db);

        long startMs = System.currentTimeMillis();

        // 4. conn + create statement
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(MAX_ROWS);

            log.info("Executing query for user {}: {}", userId, sql.trim());

            boolean isResultSet = stmt.execute(sql.trim());

            log.info("Query executed successfully for user {}", userId);

            long durationMs = System.currentTimeMillis() - startMs;

            if (isResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    return buildResultResponse(rs, durationMs);
                }
            } else {
                int updatedCount = stmt.getUpdateCount();

                return QueryResultResponse.builder()
                        .rowCount(updatedCount)
                        .ms(durationMs)
                        .message(updatedCount + "row(s) affected")
                        .build();
            }

        } catch (SQLException ex) {
            log.warn("Playground query failed for user {}: {}", userId, ex.getMessage(), ex);
            throw new PlaygroundException("Query failed: " + ex.getMessage());
        }
    }

    public Map<String, List<Map<String, String>>> getTableSchemas(String userId) {
        // 1. get db instance
        DatabaseInstance db = dbRepository.findByUserIdAndStatusIn(userId, List.of(DbStatus.RUNNING))
                .orElseThrow(() -> new ResourceNotFoundException("No running database found"));

        // 2. get connection pool
        HikariDataSource ds = getOrCreatePool(db);
        DatabaseEngine engine = DatabaseEngine.fromId(db.getEngine());
        String schemaSql = engine.getSchemaSql();

        // 3. create conn and statement and run query
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ResultSet rs = stmt.executeQuery(schemaSql);

            Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                tables.computeIfAbsent(tableName, k -> new ArrayList<>()).add(Map.of(
                        "column_name", rs.getString("column_name"),
                        "data_type", rs.getString("data_type"),
                        "is_nullable", rs.getString("is_nullable")
                ));
            }
            return tables;

        } catch (SQLException e) {
            log.warn("Failed to fetch table schemas for user {}: {}", userId, e.getMessage(), e);
            throw new PlaygroundException("Failed to fetch tables: " + e.getMessage());
        }
    }

    public void evictPool(String dbId) {
        HikariDataSource ds = pools.remove(dbId);
        if (ds != null) {
            ds.close();
            log.info("Playground pool evicted for database {}", dbId);
        }
    }

    // private helpers

    private HikariDataSource getOrCreatePool(DatabaseInstance db) {
        return pools.computeIfAbsent(db.getId(), id -> {
            String password = new String(Base64.getDecoder().decode(db.getDbPassword()));
            DatabaseEngine engine = DatabaseEngine.fromId(db.getEngine());
            String host = piBaseProperties.getDocker().getInternalHost();
            String jdbcUrl = engine.buildJdbcUrl(host, db.getHostPort(), db.getDbName());

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(db.getDbUser());
            config.setPassword(password);
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setIdleTimeout(30_000);
            config.setConnectionTimeout(5_000);
            config.setMaxLifetime(300_000);
            config.setPoolName("playground-" + db.getId());

            log.info("Creating playground pool for database {} ({})", db.getId(), jdbcUrl);

            return new HikariDataSource(config);
        });
    }

    private void validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new PlaygroundException("SQL query is required");
        }

        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(sql).find()) {
                throw new PlaygroundException("This operation is not allowed in the playground");
            }
        }
    }

    private QueryResultResponse buildResultResponse(ResultSet rs, long durationMs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        List<String> fields = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            fields.add(meta.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < MAX_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>();

            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }

        return QueryResultResponse.builder()
                .fields(fields)
                .rows(rows)
                .rowCount(rows.size())
                .ms(durationMs)
                .build();
    }
}
