package com.optel.qxinspection.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQLite 启动时自动建表（仅创建不存在的表）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SQLiteSchemaInitializer {

    private static final String THRESHOLD_RULE_DDL = """
        CREATE TABLE IF NOT EXISTS threshold_rule (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            match_key VARCHAR(64) NOT NULL UNIQUE,
            rx_low REAL,
            rx_high REAL,
            tx_low REAL,
            tx_high REAL,
            description VARCHAR(200)
        )
    """;

    private final JdbcTemplate sqliteJdbc;

    @PostConstruct
    public void init() {
        // 门限规则表：旧结构含 level_type 列，检测到即迁移旧行（用户改过的门限值必须保留）
        if (columnExists("threshold_rule", "level_type")) {
            migrateLegacyThresholdRule();
        }
        createTableIfNotExists("threshold_rule", THRESHOLD_RULE_DDL);

        createTableIfNotExists("device_access_config", """
            CREATE TABLE IF NOT EXISTS device_access_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ne_id VARCHAR(64) NOT NULL UNIQUE,
                ne_name VARCHAR(100),
                ne_type_name VARCHAR(100),
                network_name VARCHAR(200),
                ip_addr VARCHAR(50) NOT NULL,
                port INTEGER,
                username VARCHAR(100),
                password VARCHAR(200),
                enabled INTEGER DEFAULT 1,
                connection_status INTEGER DEFAULT 0,
                last_connect_time TIMESTAMP,
                create_time TIMESTAMP,
                update_time TIMESTAMP,
                remark VARCHAR(500)
            )
        """);

        createTableIfNotExists("sys_config", """
            CREATE TABLE IF NOT EXISTS sys_config (
                config_key VARCHAR(64) PRIMARY KEY,
                config_value VARCHAR(512)
            )
        """);

        createTableIfNotExists("conn_profile", """
            CREATE TABLE IF NOT EXISTS conn_profile (
                scope VARCHAR(10) NOT NULL,
                ne_oid VARCHAR(64) NOT NULL,
                username VARCHAR(100) NOT NULL,
                password VARCHAR(200) NOT NULL,
                port INTEGER NOT NULL DEFAULT 9900,
                auto_connect INTEGER NOT NULL DEFAULT 1,
                create_time TIMESTAMP,
                update_time TIMESTAMP,
                PRIMARY KEY (scope, ne_oid)
            )
        """);

        createTableIfNotExists("inspection_round", """
            CREATE TABLE IF NOT EXISTS inspection_round (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trigger_type VARCHAR(20) NOT NULL,
                scope_type VARCHAR(20) NOT NULL,
                scope_param VARCHAR(200),
                status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
                total_count INTEGER DEFAULT 0,
                done_count INTEGER DEFAULT 0,
                fail_count INTEGER DEFAULT 0,
                start_time TIMESTAMP,
                end_time TIMESTAMP
            )
        """);

        createTableIfNotExists("audit_log", """
            CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                op_time TIMESTAMP NOT NULL,
                op_type VARCHAR(32) NOT NULL,
                target VARCHAR(128),
                result VARCHAR(16),
                remark VARCHAR(500)
            )
        """);
        createIndexIfNotExists("idx_audit_time", "audit_log", "op_time DESC");

        // 业务同步表（由 DynamicSyncService 写入，含冗余字段）
        createTableIfNotExists("dmeo", """
            CREATE TABLE IF NOT EXISTS dmeo (
                oid TEXT NOT NULL,
                cid INTEGER,
                type INTEGER,
                name TEXT,
                defName TEXT,
                networkOid TEXT,
                networkName TEXT,
                neName TEXT,
                neTypeName TEXT,
                ipAddr TEXT,
                PRIMARY KEY (oid)
            )
        """);

        addColumnIfNotExists("dmeo", "networkOid", "TEXT");
        addColumnIfNotExists("dmeo", "networkName", "TEXT");
        addColumnIfNotExists("dmeo", "neName", "TEXT");
        addColumnIfNotExists("dmeo", "neTypeName", "TEXT");
        addColumnIfNotExists("dmeo", "ipAddr", "TEXT");

        // dmconnection：旧结构镜像 MySQL 原表（type/signaldm/direct/aChs/zChs 均为 NOT NULL），
        // 新结构不再写这些列，会触发 NOT NULL 约束失败，故检测到即一次性重建。
        // 该表是纯同步缓存（每次同步先清空再写入），重建不损失业务数据。
        if (columnExists("dmconnection", "type")) {
            log.info("检测到 dmconnection 旧结构（含 type），重建表");
            dropTableIfExists("dmconnection");
        }
        createTableIfNotExists("dmconnection", """
            CREATE TABLE IF NOT EXISTS dmconnection (
                oid TEXT NOT NULL,
                cid INTEGER NOT NULL DEFAULT 100,
                name TEXT,
                aEnd TEXT,
                zEnd TEXT,
                createTime INTEGER,
                creator TEXT,
                additionInfo TEXT,
                aNeName TEXT,
                aNeTypeName TEXT,
                aNetworkName TEXT,
                aPortName TEXT,
                aCapacity INTEGER,
                aUsed INTEGER,
                zNeName TEXT,
                zNeTypeName TEXT,
                zNetworkName TEXT,
                zPortName TEXT,
                zCapacity INTEGER,
                zUsed INTEGER,
                PRIMARY KEY (oid)
            )
        """);

        // 链路巡检结果表（替代 optical_power_inspection）
        createTableIfNotExists("link_inspection_result", """
            CREATE TABLE IF NOT EXISTS link_inspection_result (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                round_id INTEGER NOT NULL,
                link_oid TEXT,
                link_name TEXT,
                create_time TEXT,
                a_ne_id TEXT,
                a_ne_name TEXT,
                a_ne_type_name TEXT,
                a_port_oid TEXT,
                a_port_name TEXT,
                a_module_type TEXT,
                a_tx_power REAL,
                a_rx_power REAL,
                a_tx_status TEXT,
                a_rx_status TEXT,
                a_rs_error_sec INTEGER,
                a_total_bandwidth INTEGER,
                a_used_bandwidth INTEGER,
                a_bandwidth_usage REAL,
                a_error_info TEXT,
                z_ne_id TEXT,
                z_ne_name TEXT,
                z_ne_type_name TEXT,
                z_port_oid TEXT,
                z_port_name TEXT,
                z_module_type TEXT,
                z_tx_power REAL,
                z_rx_power REAL,
                z_tx_status TEXT,
                z_rx_status TEXT,
                z_rs_error_sec INTEGER,
                z_total_bandwidth INTEGER,
                z_used_bandwidth INTEGER,
                z_bandwidth_usage REAL,
                z_error_info TEXT
            )
        """);
        addColumnIfNotExists("dmconnection", "aNeName", "TEXT");
        addColumnIfNotExists("dmconnection", "aNeTypeName", "TEXT");
        addColumnIfNotExists("dmconnection", "aNetworkName", "TEXT");
        addColumnIfNotExists("dmconnection", "aPortName", "TEXT");
        addColumnIfNotExists("dmconnection", "aCapacity", "INTEGER");
        addColumnIfNotExists("dmconnection", "aUsed", "INTEGER");
        addColumnIfNotExists("dmconnection", "zNeName", "TEXT");
        addColumnIfNotExists("dmconnection", "zNeTypeName", "TEXT");
        addColumnIfNotExists("dmconnection", "zNetworkName", "TEXT");
        addColumnIfNotExists("dmconnection", "zPortName", "TEXT");
        addColumnIfNotExists("dmconnection", "zCapacity", "INTEGER");
        addColumnIfNotExists("dmconnection", "zUsed", "INTEGER");

        createIndexIfNotExists("idx_lir_round", "link_inspection_result", "round_id");
        createIndexIfNotExists("idx_lir_link", "link_inspection_result", "link_oid");

        // 清理旧同步表（已被 dmeo/dmconnection 冗余字段替代）
        dropTableIfExists("dmne");
        dropTableIfExists("defdmne");
        dropTableIfExists("emnecomm");
        dropTableIfExists("dmrelation");
        dropTableIfExists("dmnet");
        dropTableIfExists("defdmnetwork");
        dropTableIfExists("portbandwidth");

        // 端口维度已下线（巡检改为链路口径）
        dropTableIfExists("optical_power_inspection");
        dropTableIfExists("port_watched");

        log.info("SQLite 表结构初始化完成");
    }

    /**
     * 旧 threshold_rule 为 (level_type, match_key) 复合唯一，新结构只以 match_key 唯一。
     * 旧表 level_type 取值为 GLOBAL / MODULE，match_key 本身已全局唯一（"GLOBAL" 或模块类型键），
     * 故按 match_key 直接迁移；拷贝成功才删旧表，失败则保留为 threshold_rule_legacy 供人工恢复。
     */
    private void migrateLegacyThresholdRule() {
        if (tableExists("threshold_rule_legacy")) {
            log.error("threshold_rule_legacy already exists (previous migration incomplete), "
                    + "skipping migration; verify and drop it manually");
            return;
        }
        log.info("Legacy threshold_rule detected (has level_type), migrating rows");
        try {
            sqliteJdbc.execute("ALTER TABLE threshold_rule RENAME TO threshold_rule_legacy");
        } catch (Exception e) {
            log.error("Failed to rename legacy threshold_rule, migration skipped (original table untouched)", e);
            return;
        }
        try {
            createTableIfNotExists("threshold_rule", THRESHOLD_RULE_DDL);
            int migrated = sqliteJdbc.update("""
                INSERT OR IGNORE INTO threshold_rule
                    (match_key, rx_low, rx_high, tx_low, tx_high, description)
                SELECT match_key, rx_low, rx_high, tx_low, tx_high, description
                FROM threshold_rule_legacy
                ORDER BY id
            """);
            sqliteJdbc.execute("DROP TABLE threshold_rule_legacy");
            log.info("threshold_rule migrated: {} rows", migrated);
        } catch (Exception e) {
            log.error("threshold_rule migration failed, legacy rows kept in threshold_rule_legacy", e);
        }
    }

    private void createTableIfNotExists(String tableName, String sql) {
        try {
            sqliteJdbc.execute(sql);
            log.debug("表 {} 检查/创建完成", tableName);
        } catch (Exception e) {
            log.warn("创建表 {} 失败: {}", tableName, e.getMessage());
        }
    }

    private void createIndexIfNotExists(String indexName, String tableName, String columns) {
        try {
            sqliteJdbc.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + " (" + columns + ")");
        } catch (Exception e) {
            log.debug("索引 {} 已存在或创建失败", indexName);
        }
    }

    private boolean tableExists(String tableName) {
        try {
            Long count = sqliteJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                    Long.class, tableName);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("Failed to check existence of table {}: {}", tableName, e.getMessage());
            return false;
        }
    }

    private void dropTableIfExists(String tableName) {
        if (!tableExists(tableName)) {
            return;
        }
        try {
            sqliteJdbc.execute("DROP TABLE \"" + tableName + "\"");
            log.info("已清理旧表: {}", tableName);
        } catch (Exception e) {
            log.debug("清理旧表 {} 失败: {}", tableName, e.getMessage());
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        try {
            List<String> columns = sqliteJdbc.queryForList(
                    "SELECT name FROM pragma_table_info(?)", String.class, tableName);
            return columns.stream().anyMatch(columnName::equalsIgnoreCase);
        } catch (Exception e) {
            log.debug("查询表 {} 结构失败: {}", tableName, e.getMessage());
            return false;
        }
    }

    private void addColumnIfNotExists(String tableName, String column, String type) {
        try {
            sqliteJdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + column + " " + type);
            log.info("表 {} 新增列 {}", tableName, column);
        } catch (Exception e) {
            // 列已存在时 SQLite 会报错，忽略即可
            log.debug("表 {} 列 {} 已存在或添加失败: {}", tableName, column, e.getMessage());
        }
    }
}
