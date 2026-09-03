package com.optel.qxinspection.service;

import com.optel.qxinspection.config.SyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DynamicSyncService {

    private final JdbcTemplate sqliteJdbc;
    private final MysqlConnectionManager mysqlConnectionManager;
    private final SyncConfig syncConfig;
    private final TransactionTemplate sqliteTransactionTemplate;

    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
            Map.entry("varchar", "TEXT"),
            Map.entry("char", "TEXT"),
            Map.entry("text", "TEXT"),
            Map.entry("longtext", "TEXT"),
            Map.entry("mediumtext", "TEXT"),
            Map.entry("tinytext", "TEXT"),
            Map.entry("blob", "BLOB"),
            Map.entry("longblob", "BLOB"),
            Map.entry("mediumblob", "BLOB"),
            Map.entry("tinyblob", "BLOB"),
            Map.entry("int", "INTEGER"),
            Map.entry("integer", "INTEGER"),
            Map.entry("bigint", "INTEGER"),
            Map.entry("smallint", "INTEGER"),
            Map.entry("tinyint", "INTEGER"),
            Map.entry("mediumint", "INTEGER"),
            Map.entry("float", "REAL"),
            Map.entry("double", "REAL"),
            Map.entry("decimal", "REAL"),
            Map.entry("numeric", "REAL"),
            Map.entry("date", "TEXT"),
            Map.entry("datetime", "TEXT"),
            Map.entry("timestamp", "TEXT"),
            Map.entry("time", "TEXT"),
            Map.entry("year", "INTEGER"),
            Map.entry("bit", "INTEGER"),
            Map.entry("boolean", "INTEGER"),
            Map.entry("enum", "TEXT"),
            Map.entry("set", "TEXT"),
            Map.entry("json", "TEXT")
    );

    public DynamicSyncService(@Qualifier("sqliteDataSource") DataSource sqliteDs,
                               MysqlConnectionManager mysqlConnectionManager,
                               SyncConfig syncConfig,
                               @Qualifier("sqliteTransactionManager") org.springframework.transaction.PlatformTransactionManager sqliteTxManager) {
        this.sqliteJdbc = new JdbcTemplate(sqliteDs);
        this.mysqlConnectionManager = mysqlConnectionManager;
        this.syncConfig = syncConfig;
        this.sqliteTransactionTemplate = new TransactionTemplate(sqliteTxManager);
    }

    /**
     * 获取 MySQL 中所有用户表
     */
    public List<String> getAllTables() {
        JdbcTemplate mysqlJdbc = mysqlConnectionManager.getJdbcTemplate();
        return mysqlJdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' " +
                "ORDER BY TABLE_NAME",
                String.class
        );
    }

    /**
     * 获取同步状态摘要
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        List<String> allTables;
        try {
            allTables = getAllTables();
        } catch (Exception e) {
            status.put("error", "MySQL 未连接: " + e.getMessage());
            status.put("totalTables", 0);
            status.put("essentialTables", syncConfig.getEssential());
            status.put("syncedCount", 0);
            status.put("notSyncedCount", 0);
            status.put("synced", List.of());
            status.put("notSynced", List.of());
            return status;
        }
        List<String> essential = syncConfig.getEssential();
        List<String> exclude = syncConfig.getExclude();

        List<String> synced = new ArrayList<>();
        List<String> notSynced = new ArrayList<>();

        for (String table : allTables) {
            if (exclude.contains(table)) continue;
            if (isTableExistsSqlite(table)) {
                long count = sqliteJdbc.queryForObject(
                        "SELECT COUNT(*) FROM \"" + table + "\"", Long.class);
                synced.add(table + " (" + count + ")");
            } else {
                notSynced.add(table);
            }
        }

        status.put("totalTables", allTables.size());
        status.put("essentialTables", essential);
        status.put("syncedCount", synced.size());
        status.put("notSyncedCount", notSynced.size());
        status.put("synced", synced);
        status.put("notSynced", notSynced);
        return status;
    }

    /**
     * 同步全部表
     */
    public Map<String, Object> syncAll() {
        List<String> tables = getAllTables();
        tables.removeAll(syncConfig.getExclude());
        return syncTables(tables);
    }

    /**
     * 同步必要表
     */
    public Map<String, Object> syncEssential() {
        return syncTables(syncConfig.getEssential());
    }

    /**
     * 同步指定表
     */
    public Map<String, Object> syncTables(List<String> tables) {
        JdbcTemplate mysqlJdbc = mysqlConnectionManager.getJdbcTemplate();
        Map<String, Object> result = new LinkedHashMap<>();
        long totalRows = 0;
        long startTime = System.currentTimeMillis();

        try {
            for (String table : tables) {
                try {
                    long rows = syncSingleTable(mysqlJdbc, table);
                    result.put(table, rows);
                    totalRows += rows;
                } catch (Exception e) {
                    log.error("同步表 {} 失败", table, e);
                    result.put(table, "ERROR: " + e.getMessage());
                }
            }
        } finally {
            mysqlConnectionManager.close();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        result.put("_summary", String.format("同步完成: %d 张表, %d 行, 耗时 %d ms",
                tables.size(), totalRows, elapsed));
        return result;
    }

    /**
     * 同步单张表
     */
    private long syncSingleTable(JdbcTemplate mysqlJdbc, String table) {
        log.info("开始同步表: {}", table);

        // 1. 获取 MySQL 表结构
        List<Map<String, String>> columns = getMysqlColumns(mysqlJdbc, table);
        if (columns.isEmpty()) {
            throw new RuntimeException("同步表结构获取失败，请检查 MySQL 连接是否正常");
        }

        // 2. 获取主键列
        List<String> primaryKeys = getMysqlPrimaryKeys(mysqlJdbc, table);

        // 3. 从 MySQL 读取所有数据（不在事务中）
        String mysqlSql = "SELECT * FROM `" + table + "`";
        String[] colNames = columns.stream()
                .map(c -> c.get("COLUMN_NAME"))
                .toArray(String[]::new);
        String placeholders = Arrays.stream(colNames)
                .map(c -> "?")
                .collect(Collectors.joining(","));
        String sqliteSql = "INSERT INTO \"" + table + "\" (" +
                Arrays.stream(colNames).map(c -> "\"" + c + "\"").collect(Collectors.joining(","))
                + ") VALUES (" + placeholders + ")";

        int batchSize = syncConfig.getBatchSize();
        List<Object[]> allData = new ArrayList<>();
        int offset = 0;
        while (true) {
            String pagedSql = mysqlSql + " LIMIT " + batchSize + " OFFSET " + offset;
            List<Map<String, Object>> rows = mysqlJdbc.queryForList(pagedSql);
            if (rows.isEmpty()) break;

            for (Map<String, Object> row : rows) {
                Object[] values = new Object[colNames.length];
                for (int i = 0; i < colNames.length; i++) {
                    values[i] = row.get(colNames[i]);
                }
                allData.add(values);
            }
            offset += batchSize;
            if (rows.size() < batchSize) break;
        }
        log.info("表 {} 从 MySQL 读取 {} 行", table, allData.size());

        // 4. 在事务中写入 SQLite（建表 + 清空 + 批量插入）
        final long[] rowCount = {0};
        sqliteTransactionTemplate.execute(status -> {
            createSqliteTable(table, columns, primaryKeys);
            sqliteJdbc.execute("DELETE FROM \"" + table + "\"");

            // 分批写入
            for (int i = 0; i < allData.size(); i += batchSize) {
                List<Object[]> batch = allData.subList(i, Math.min(i + batchSize, allData.size()));
                batchInsert(sqliteSql, batch);
                rowCount[0] += batch.size();
            }
            return null;
        });

        log.info("表 {} 同步完成, 共 {} 行", table, rowCount[0]);
        return rowCount[0];
    }

    /**
     * 获取 MySQL 表的列信息
     */
    private List<Map<String, String>> getMysqlColumns(JdbcTemplate mysqlJdbc, String table) {
        return mysqlJdbc.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, " +
                "CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE " +
                "FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                "ORDER BY ORDINAL_POSITION",
                table
        ).stream().map(row -> {
            Map<String, String> col = new LinkedHashMap<>();
            col.put("COLUMN_NAME", (String) row.get("COLUMN_NAME"));
            col.put("DATA_TYPE", (String) row.get("DATA_TYPE"));
            col.put("IS_NULLABLE", (String) row.get("IS_NULLABLE"));
            col.put("COLUMN_DEFAULT", row.get("COLUMN_DEFAULT") != null ?
                    row.get("COLUMN_DEFAULT").toString() : null);
            col.put("CHARACTER_MAXIMUM_LENGTH", row.get("CHARACTER_MAXIMUM_LENGTH") != null ?
                    row.get("CHARACTER_MAXIMUM_LENGTH").toString() : null);
            return col;
        }).collect(Collectors.toList());
    }

    /**
     * 获取 MySQL 表的主键列
     */
    private List<String> getMysqlPrimaryKeys(JdbcTemplate mysqlJdbc, String table) {
        return mysqlJdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' " +
                "ORDER BY ORDINAL_POSITION",
                table
        ).stream().map(row -> (String) row.get("COLUMN_NAME")).collect(Collectors.toList());
    }

    /**
     * 在 SQLite 中动态建表
     */
    private void createSqliteTable(String table, List<Map<String, String>> columns,
                                    List<String> primaryKeys) {
        sqliteJdbc.execute("DROP TABLE IF EXISTS \"" + table + "\"");

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE \"").append(table).append("\" (\n");

        List<String> colDefs = new ArrayList<>();
        for (Map<String, String> col : columns) {
            String colName = col.get("COLUMN_NAME");
            String sqliteType = mapType(col.get("DATA_TYPE"));
            String nullable = "YES".equals(col.get("IS_NULLABLE")) ? "" : " NOT NULL";
            colDefs.add("  \"" + colName + "\" " + sqliteType + nullable);
        }

        if (!primaryKeys.isEmpty()) {
            String pkCols = primaryKeys.stream()
                    .map(pk -> "\"" + pk + "\"")
                    .collect(Collectors.joining(","));
            colDefs.add("  PRIMARY KEY (" + pkCols + ")");
        }

        sql.append(String.join(",\n", colDefs));
        sql.append("\n)");

        log.debug("建表 SQL: {}", sql);
        sqliteJdbc.execute(sql.toString());
    }

    private String mapType(String mysqlType) {
        if (mysqlType == null) return "TEXT";
        String lower = mysqlType.toLowerCase();
        String base = lower.contains("(") ? lower.substring(0, lower.indexOf("(")) : lower;
        return TYPE_MAP.getOrDefault(base, "TEXT");
    }

    private void batchInsert(String sql, List<Object[]> batch) {
        sqliteJdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Object[] row = batch.get(i);
                for (int j = 0; j < row.length; j++) {
                    Object val = row[j];
                    if (val == null) {
                        ps.setNull(j + 1, Types.NULL);
                    } else if (val instanceof Number) {
                        ps.setObject(j + 1, val);
                    } else if (val instanceof java.util.Date) {
                        ps.setString(j + 1, val.toString());
                    } else if (val instanceof java.sql.Timestamp) {
                        ps.setString(j + 1, val.toString());
                    } else if (val instanceof java.sql.Date) {
                        ps.setString(j + 1, val.toString());
                    } else if (val instanceof byte[]) {
                        ps.setBytes(j + 1, (byte[]) val);
                    } else {
                        ps.setString(j + 1, val.toString());
                    }
                }
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    private boolean isTableExistsSqlite(String table) {
        Long count = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                Long.class, table);
        return count != null && count > 0;
    }

    /**
     * 清除所有动态同步的表
     */
    public Map<String, Long> clearSyncData() {
        Map<String, Long> result = new LinkedHashMap<>();
        List<String> allTables = getSyncedTableNames();
        log.info("clearSyncData: 发现 {} 张同步表待清除", allTables.size());
        sqliteTransactionTemplate.executeWithoutResult(status -> {
            for (String table : allTables) {
                if (isTableExistsSqlite(table)) {
                    long count = sqliteJdbc.queryForObject(
                            "SELECT COUNT(*) FROM \"" + table + "\"", Long.class);
                    sqliteJdbc.execute("DROP TABLE \"" + table + "\"");
                    result.put(table, count);
                }
            }
        });
        log.info("clearSyncData: 已清除 {} 张表", result.size());
        return result;
    }

    /**
     * 获取 SQLite 中已同步的表名（不依赖 MySQL 连接）
     */
    private List<String> getSyncedTableNames() {
        return sqliteJdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('device_access_config','conn_profile','inspection_round'," +
                "'optical_power_inspection','threshold_rule','sys_config')",
                String.class
        );
    }

    /**
     * 从 SQLite 动态查询
     */
    public List<Map<String, Object>> query(String table, List<String> fields,
                                            Map<String, Object> conditions, String orderBy,
                                            Integer limit) {
        validateIdentifier(table, "table");

        StringBuilder sql = new StringBuilder("SELECT ");

        if (fields == null || fields.isEmpty()) {
            sql.append("*");
        } else {
            sql.append(fields.stream()
                    .peek(f -> validateIdentifier(f, "field"))
                    .map(f -> "\"" + f + "\"")
                    .collect(Collectors.joining(", ")));
        }

        sql.append(" FROM \"").append(table).append("\"");

        List<Object> params = new ArrayList<>();
        if (conditions != null && !conditions.isEmpty()) {
            String where = conditions.entrySet().stream()
                    .map(e -> {
                        validateIdentifier(e.getKey(), "condition field");
                        params.add(e.getValue());
                        return "\"" + e.getKey() + "\" = ?";
                    })
                    .collect(Collectors.joining(" AND "));
            sql.append(" WHERE ").append(where);
        }

        if (orderBy != null && !orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(sanitizeOrderBy(orderBy));
        }

        if (limit != null && limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }

        return sqliteJdbc.queryForList(sql.toString(), params.toArray());
    }

    private static final java.util.regex.Pattern IDENTIFIER_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static void validateIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
    }

    private static String sanitizeOrderBy(String orderBy) {
        // 允许 "column" 或 "column ASC" / "column DESC"
        String[] parts = orderBy.trim().split("\\s+");
        validateIdentifier(parts[0], "orderBy column");
        if (parts.length > 1) {
            String dir = parts[1].toUpperCase(Locale.ROOT);
            if (!dir.equals("ASC") && !dir.equals("DESC")) {
                throw new IllegalArgumentException("Invalid orderBy direction: " + parts[1]);
            }
            return "\"" + parts[0] + "\" " + dir;
        }
        return "\"" + parts[0] + "\"";
    }
}
