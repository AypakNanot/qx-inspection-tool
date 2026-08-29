package com.optel.qxinspection.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite 启动时自动建表（仅创建不存在的表）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SQLiteSchemaInitializer {

    private final JdbcTemplate sqliteJdbc;

    @PostConstruct
    public void init() {
        createTableIfNotExists("threshold_rule", """
            CREATE TABLE IF NOT EXISTS threshold_rule (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                level_type VARCHAR(20) NOT NULL,
                match_key VARCHAR(64) NOT NULL,
                rx_low REAL,
                rx_high REAL,
                tx_low REAL,
                tx_high REAL,
                description VARCHAR(200),
                UNIQUE(level_type, match_key)
            )
        """);

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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                config_key VARCHAR(100) NOT NULL UNIQUE,
                config_value VARCHAR(500),
                description VARCHAR(200)
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
                status VARCHAR(20),
                start_time TIMESTAMP,
                end_time TIMESTAMP,
                total_count INTEGER DEFAULT 0,
                done_count INTEGER DEFAULT 0,
                fail_count INTEGER DEFAULT 0,
                scope_type VARCHAR(20),
                scope_param VARCHAR(200),
                create_time TIMESTAMP
            )
        """);

        createTableIfNotExists("optical_power_inspection", """
            CREATE TABLE IF NOT EXISTS optical_power_inspection (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                round_id INTEGER NOT NULL,
                ne_id VARCHAR(64) NOT NULL,
                ne_name VARCHAR(100),
                network_name VARCHAR(100),
                ne_type_name VARCHAR(100),
                slot_no INTEGER,
                port_no INTEGER,
                port_name VARCHAR(256),
                port_type INTEGER,
                port_sub_type INTEGER,
                supported INTEGER,
                laser_state INTEGER,
                laser_type VARCHAR(20),
                laser_distance VARCHAR(20),
                module_type_key VARCHAR(40),
                part_number VARCHAR(32),
                vendor_name VARCHAR(32),
                laser_wave VARCHAR(20),
                tx_power REAL,
                rx_power REAL,
                tx_power_status INTEGER DEFAULT 0,
                rx_power_status INTEGER DEFAULT 0,
                low_threshold REAL,
                high_threshold REAL,
                tx_low_threshold REAL,
                tx_high_threshold REAL,
                inspection_time TIMESTAMP,
                create_time TIMESTAMP,
                fail_reason VARCHAR(200)
            )
        """);

        // 创建索引
        createIndexIfNotExists("idx_opi_round", "optical_power_inspection", "round_id");
        createIndexIfNotExists("idx_opi_ne", "optical_power_inspection", "ne_id");
        createIndexIfNotExists("idx_opi_round_ne", "optical_power_inspection", "round_id,ne_id");

        log.info("SQLite 表结构初始化完成");
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
}
