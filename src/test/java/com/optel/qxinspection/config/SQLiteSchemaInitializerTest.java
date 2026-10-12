package com.optel.qxinspection.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite 建表 / 迁移测试（真实 sqlite-jdbc 驱动，非 mock）。
 * <p>重点覆盖旧结构迁移：dmconnection 早期镜像 MySQL 原表，type/signaldm/direct/aChs/zChs
 * 都是 NOT NULL，而新同步语句不写这些列。CREATE TABLE IF NOT EXISTS 不会改变已存在表的约束，
 * 于是链路写入必然违反 NOT NULL 并让整个同步事务回滚。</p>
 */
class SQLiteSchemaInitializerTest {

    private static final String DMCONNECTION = "dmconnection";
    private static final String THRESHOLD_RULE = "threshold_rule";
    private static final String OVERLAY_KEY = "threshold.overlay.version";
    private static final String OVERLAY_VERSION = "1";

    private static final String INSERT_LINK_SQL =
            "INSERT INTO dmconnection (oid, cid, name, aEnd, zEnd, createTime, creator, additionInfo, "
                    + "aNeName, aNeTypeName, aNetworkName, aPortName, aCapacity, aUsed, "
                    + "zNeName, zNeTypeName, zNetworkName, zPortName, zCapacity, zUsed) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("schema.db").toString().replace('\\', '/'));
        jdbc = new JdbcTemplate(dataSource);
    }

    private void newInitializer() {
        new SQLiteSchemaInitializer(jdbc).init();
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class, table);
    }

    private void insertNewShapeLink() {
        jdbc.update(INSERT_LINK_SQL, "9001", 100, "L1", "101:1:11:1", "102:1:11:2", 1766000000, "root", null,
                "NE101", "MatrixEdge2050", "上饶南区_地调", "P1(1/11/1)", 1008, 21,
                "NE102", "MatrixEdge2050", "上饶南区_地调", "P2(1/11/2)", 1008, 21);
    }

    @Test
    void init_FreshDb_CreatesDmconnectionAcceptingNewShapeInsert() {
        newInitializer();

        List<String> columns = columnsOf(DMCONNECTION);
        assertFalse(columns.contains("type"), "新结构不应再有 type 列");
        assertFalse(columns.contains("signaldm"));
        assertTrue(columns.containsAll(List.of("oid", "cid", "name", "aEnd", "zEnd", "aNeName",
                "aNeTypeName", "aNetworkName", "aPortName", "aCapacity", "aUsed",
                "zNeName", "zNeTypeName", "zNetworkName", "zPortName", "zCapacity", "zUsed")));

        assertDoesNotThrow(this::insertNewShapeLink);
    }

    @Test
    void init_LegacyDmconnection_RebuiltSoNewShapeInsertSucceeds() {
        jdbc.execute("""
                CREATE TABLE dmconnection (
                    oid TEXT NOT NULL, cid INTEGER NOT NULL, type INTEGER NOT NULL,
                    name TEXT NOT NULL, signaldm INTEGER NOT NULL, direct INTEGER NOT NULL,
                    aEnd TEXT NOT NULL, aChs TEXT NOT NULL, zEnd TEXT NOT NULL, zChs TEXT NOT NULL,
                    createTime INTEGER NOT NULL, creator TEXT NOT NULL, additionInfo TEXT NOT NULL,
                    PRIMARY KEY (oid))
                """);
        jdbc.update("INSERT INTO dmconnection (oid, cid, type, name, signaldm, direct, aEnd, aChs, "
                        + "zEnd, zChs, createTime, creator, additionInfo) "
                        + "VALUES ('1', 100, 1, 'L', 1, 1, 'a', '', 'z', '', 0, 'u', '')");
        assertThrows(Exception.class, this::insertNewShapeLink, "旧结构下新写入应失败（复现现场约束冲突）");

        newInitializer();

        assertFalse(columnsOf(DMCONNECTION).contains("type"));
        assertEquals(0, countOf(DMCONNECTION), "缓存表重建后为空，由下次同步回填");
        assertDoesNotThrow(this::insertNewShapeLink);
    }

    @Test
    void init_LegacyThresholdRuleWithLevelType_Rebuilt() {
        createLegacyThresholdRule();

        newInitializer();

        List<String> columns = columnsOf(THRESHOLD_RULE);
        assertFalse(columns.contains("level_type"));
        assertTrue(columns.containsAll(List.of("rx_low", "rx_high", "tx_low", "tx_high", "description")));
    }

    @Test
    void init_LegacyThresholdRule_RebuiltThenClearedForOverlayMode() {
        createLegacyThresholdRule();
        jdbc.update("INSERT INTO threshold_rule (level_type, match_key, rx_low, rx_high, tx_low, tx_high, description) "
                + "VALUES ('GLOBAL', 'GLOBAL', -20.0, -5.0, -3.0, 2.0, '全局门限')");

        newInitializer();

        // 结构迁移完成后，存量镜像行由覆盖层迁移一次性清空（开发阶段不保留存量）
        assertFalse(columnsOf(THRESHOLD_RULE).contains("level_type"));
        assertFalse(tableExists("threshold_rule_legacy"), "迁移成功后应清掉中转表");
        assertEquals(0, countOf(THRESHOLD_RULE), "覆盖层模式首次启动应清空存量行");
        assertEquals(OVERLAY_VERSION, overlayMarker(), "应写入覆盖层标记");
    }

    @Test
    void init_ExistingMirrorRowsWithoutMarker_ClearedOnce() {
        // 现状最常见的现场：新结构 + 旧版全量镜像行、还没有覆盖层标记
        newInitializer();
        jdbc.update("DELETE FROM sys_config WHERE config_key = ?", OVERLAY_KEY);
        jdbc.update("INSERT INTO threshold_rule (match_key, rx_low, rx_high, tx_low, tx_high) "
                + "VALUES ('S1.1', -28.0, -8.0, -15.0, -8.0)");

        newInitializer();

        assertEquals(0, countOf(THRESHOLD_RULE), "无标记的存量镜像行应被清空");
        assertEquals(OVERLAY_VERSION, overlayMarker(), "清空后应补写标记");

        // 标记已写入 → 再次启动不再清空（用户新保存的覆盖行不丢）
        jdbc.update("INSERT INTO threshold_rule (match_key, rx_low, rx_high, tx_low, tx_high) "
                + "VALUES ('S1.1', -20.0, -5.0, -6.0, 0.0)");
        newInitializer();
        assertEquals(1, countOf(THRESHOLD_RULE), "有标记后启动不能再清用户覆盖行");
    }

    @Test
    void init_LeftoverStagingTableFromPreviousMigration_LeavesOriginalUntouched() {
        createLegacyThresholdRule();
        jdbc.update("INSERT INTO threshold_rule (level_type, match_key, rx_low) VALUES ('GLOBAL', 'GLOBAL', -20.0)");
        jdbc.execute("CREATE TABLE threshold_rule_legacy (id INTEGER PRIMARY KEY, match_key VARCHAR(64))");

        newInitializer();

        // 上次迁移没走完，不能贸然再搬一次覆盖掉人工可恢复的那份
        assertTrue(columnsOf(THRESHOLD_RULE).contains("level_type"), "原表应保持旧结构等待人工确认");
        assertEquals(1, countOf(THRESHOLD_RULE));
    }

    @Test
    void bandwidthColumnsStillStoreRawVc12_NoDdlChangeNeeded() {
        newInitializer();

        jdbc.update("INSERT INTO link_inspection_result (round_id, a_total_bandwidth, a_used_bandwidth) "
                + "VALUES (1, ?, ?)", 1008.0, 178.0);

        // 写入侧把 VC-12 以 Double 交给 SQLite，而列声明仍是 INTEGER：
        // 整数亲和性会把 1008.0 落成 INTEGER 1008，原始 VC-12 不会在库里被放大成 Mbps
        assertEquals(1008, jdbc.queryForObject(
                "SELECT a_total_bandwidth FROM link_inspection_result", Integer.class).intValue());
        assertEquals(178, jdbc.queryForObject(
                "SELECT a_used_bandwidth FROM link_inspection_result", Integer.class).intValue());
    }

    /** 旧结构：level_type + match_key 复合唯一（与上一个版本建表语句一致） */
    private void createLegacyThresholdRule() {
        jdbc.execute("""
                CREATE TABLE threshold_rule (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    level_type VARCHAR(20) NOT NULL,
                    match_key VARCHAR(64) NOT NULL,
                    rx_low REAL, rx_high REAL, tx_low REAL, tx_high REAL,
                    description VARCHAR(200),
                    UNIQUE(level_type, match_key))
                """);
    }

    /** sys_config 中的覆盖层切换标记（未写入时返回 null） */
    private String overlayMarker() {
        List<String> marked = jdbc.queryForList(
                "SELECT config_value FROM sys_config WHERE config_key = ?", String.class, OVERLAY_KEY);
        return marked.isEmpty() ? null : marked.get(0);
    }

    private boolean tableExists(String table) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Long.class, table);
        return count != null && count > 0;
    }

    @Test
    void init_RunTwice_PreservesUserMaintainedData() {
        newInitializer();
        // 用户保存的门限覆盖行（标记已写入，不能被清掉）
        jdbc.update("INSERT INTO threshold_rule (match_key, rx_low, rx_high, tx_low, tx_high) "
                + "VALUES ('S1.1', -20.0, -5.0, -6.0, 0.0)");
        jdbc.update("INSERT INTO device_access_config (ne_id, ne_name, ip_addr) VALUES ('101', 'NE101', '1.1.1.1')");
        jdbc.update("INSERT INTO sys_config (config_key, config_value) VALUES ('mysql.username', 'root')");
        insertNewShapeLink();

        newInitializer();

        assertEquals(1, countOf("device_access_config"));
        assertEquals(1, countOf(THRESHOLD_RULE), "第二次启动不能清掉用户覆盖行");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_config WHERE config_key = 'mysql.username'", Long.class).longValue());
        assertEquals(1, countOf(DMCONNECTION), "已是新结构时不应被重建");
    }

    private long countOf(String table) {
        Long count = switch (table) {
            case DMCONNECTION -> jdbc.queryForObject("SELECT COUNT(*) FROM dmconnection", Long.class);
            case THRESHOLD_RULE -> jdbc.queryForObject("SELECT COUNT(*) FROM threshold_rule", Long.class);
            case "device_access_config" -> jdbc.queryForObject("SELECT COUNT(*) FROM device_access_config", Long.class);
            default -> throw new IllegalArgumentException("未支持的表: " + table);
        };
        return count != null ? count : 0L;
    }
}
