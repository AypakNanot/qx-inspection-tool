package com.optel.qxinspection.service;

import com.optel.qxinspection.util.OidUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务同步服务 —— 从 MySQL 按网络过滤，构建冗余字段写入 SQLite 的 dmeo / dmconnection 表。
 */
@Slf4j
@Service
public class DynamicSyncService {

    private static final DateTimeFormatter SYNC_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int BATCH_SIZE = 5000;

    /** dmconnection cid：链路（其余 cid 为交叉连接/路径/路径段/以太路径，巡检不使用） */
    private static final int CID_LINK = 100;

    private static final String KEY_NETWORK_IDS = "sync.networkIds";
    private static final String KEY_NETWORK_NAMES = "sync.networkNames";
    private static final String KEY_SYNC_TIME = "sync.time";
    private static final String KEY_SYNC_STATUS = "sync.status";

    private static final String INSERT_DMEO_SQL =
            "INSERT INTO dmeo (oid, cid, type, name, defName, networkOid, networkName, neName, neTypeName, ipAddr) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String INSERT_DMCONNECTION_SQL =
            "INSERT INTO dmconnection (oid, cid, name, aEnd, zEnd, createTime, creator, additionInfo, "
                    + "aNeName, aNeTypeName, aNetworkName, aPortName, aCapacity, aUsed, "
                    + "zNeName, zNeTypeName, zNetworkName, zPortName, zCapacity, zUsed) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate sqliteJdbc;
    private final MysqlConnectionManager mysqlConnectionManager;
    private final TransactionTemplate sqliteTransactionTemplate;

    public DynamicSyncService(@Qualifier("sqliteJdbc") JdbcTemplate sqliteJdbc,
                              MysqlConnectionManager mysqlConnectionManager,
                              @Qualifier("sqliteTransactionManager") PlatformTransactionManager sqliteTxManager) {
        this.sqliteJdbc = sqliteJdbc;
        this.mysqlConnectionManager = mysqlConnectionManager;
        this.sqliteTransactionTemplate = new TransactionTemplate(sqliteTxManager);
    }

    // ==================== 同步元数据 ====================

    /** 从 sys_config 读取已同步的网络 ID 列表 */
    public List<String> getSyncedNetworkIds() {
        return splitConfig(KEY_NETWORK_IDS);
    }

    /** 从 sys_config 读取已同步的网络名称列表 */
    public List<String> getSyncedNetworkNames() {
        return splitConfig(KEY_NETWORK_NAMES);
    }

    /** 从 sys_config 读取同步时间 */
    public String getSyncTime() {
        return getConfigValue(KEY_SYNC_TIME);
    }

    /** 从 sys_config 读取同步状态 */
    public String getSyncStatus() {
        return getConfigValue(KEY_SYNC_STATUS);
    }

    private List<String> splitConfig(String key) {
        String val = getConfigValue(key);
        if (val == null || val.isBlank()) {
            return List.of();
        }
        return Arrays.asList(val.split(","));
    }

    private String getConfigValue(String key) {
        try {
            return sqliteJdbc.queryForObject(
                    "SELECT config_value FROM sys_config WHERE config_key = ?",
                    String.class, key);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private void setConfigValue(String key, String value) {
        sqliteJdbc.update(
                "INSERT OR REPLACE INTO sys_config (config_key, config_value) VALUES (?, ?)",
                key, value);
    }

    // ==================== 获取可同步的网络列表 ====================

    /**
     * 从 MySQL 获取所有网络（dmeo cid=1），返回 [{oid, name}]。
     */
    public List<Map<String, Object>> getAvailableNetworks() {
        JdbcTemplate mysqlJdbc = mysqlConnectionManager.getJdbcTemplate();
        try {
            return mysqlJdbc.queryForList(
                    "SELECT oid, name FROM dmeo WHERE cid = 1 ORDER BY name");
        } finally {
            mysqlConnectionManager.close();
        }
    }

    // ==================== 同步状态摘要 ====================

    public Map<String, Object> getSyncStatusSummary() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("networkIds", getConfigValue(KEY_NETWORK_IDS));
        status.put("networkNames", getConfigValue(KEY_NETWORK_NAMES));
        status.put("syncTime", getConfigValue(KEY_SYNC_TIME));
        status.put("syncStatus", getConfigValue(KEY_SYNC_STATUS));
        Long neCount = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM dmeo WHERE cid = 2", Long.class);
        status.put("neCount", neCount != null ? neCount : 0L);
        Long linkCount = countRows("dmconnection");
        status.put("linkCount", linkCount);
        Long portCount = sqliteJdbc.queryForObject(
                "SELECT COUNT(DISTINCT port) FROM ("
                        + "SELECT aEnd AS port FROM dmconnection UNION SELECT zEnd AS port FROM dmconnection"
                        + ")", Long.class);
        status.put("portCount", portCount != null ? portCount : 0L);
        return status;
    }

    private long countRows(String table) {
        Long count = sqliteJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count != null ? count : 0L;
    }

    // ==================== 核心同步 ====================

    /**
     * 按指定网络 OID 列表执行同步。
     * 1. 从 MySQL 读取基础数据
     * 2. 构建内存索引
     * 3. 过滤并写入 dmeo（含冗余字段）
     * 4. 过滤并写入 dmconnection（含冗余字段）
     * 5. 更新 sys_config
     */
    public Map<String, Object> syncNetworks(List<String> networkOids) {
        if (networkOids == null || networkOids.isEmpty()) {
            throw new IllegalArgumentException("网络列表不能为空");
        }

        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            setConfigValue(KEY_SYNC_STATUS, "RUNNING");

            JdbcTemplate mysqlJdbc = mysqlConnectionManager.getJdbcTemplate();
            log.info("开始从 MySQL 读取基础数据...");
            List<Map<String, Object>> allDmeo = mysqlJdbc.queryForList("SELECT * FROM dmeo");
            List<Map<String, Object>> allDmconnection =
                    mysqlJdbc.queryForList("SELECT * FROM dmconnection WHERE cid = ?", CID_LINK);
            SyncIndex index = buildIndex(allDmeo,
                    mysqlJdbc.queryForList("SELECT * FROM dmrelation"),
                    mysqlJdbc.queryForList("SELECT * FROM defdmne"),
                    mysqlJdbc.queryForList("SELECT * FROM emnecomm"),
                    mysqlJdbc.queryForList("SELECT * FROM portbandwidth"));

            Set<String> targetNetworks = new HashSet<>(networkOids);
            Set<String> targetNeOids = index.neOidsIn(targetNetworks);
            List<Object[]> dmeoRows = buildDmeoRows(allDmeo, targetNetworks, index);
            List<Object[]> connRows = buildConnectionRows(allDmconnection, targetNeOids, index);

            log.info("同步过滤: dmeo {}→{}, dmconnection {}→{}",
                    allDmeo.size(), dmeoRows.size(), allDmconnection.size(), connRows.size());

            sqliteTransactionTemplate.executeWithoutResult(status -> {
                sqliteJdbc.execute("DELETE FROM dmeo");
                sqliteJdbc.execute("DELETE FROM dmconnection");
                insertRows(INSERT_DMEO_SQL, dmeoRows);
                insertRows(INSERT_DMCONNECTION_SQL, connRows);
            });

            String networkNames = networkOids.stream()
                    .map(oid -> index.networkNameMap.getOrDefault(oid, oid))
                    .collect(Collectors.joining(","));
            setConfigValue(KEY_NETWORK_IDS, String.join(",", networkOids));
            setConfigValue(KEY_NETWORK_NAMES, networkNames);
            setConfigValue(KEY_SYNC_TIME, LocalDateTime.now().format(SYNC_TIME_FMT));
            setConfigValue(KEY_SYNC_STATUS, "SUCCESS");

            long elapsed = System.currentTimeMillis() - startTime;
            long neCount = dmeoRows.stream().filter(r -> toInt(r[1]) == 2).count();
            Set<String> ports = new HashSet<>();
            for (Object[] row : connRows) {
                ports.add(toStr(row[3]));  // aEnd
                ports.add(toStr(row[4]));  // zEnd
            }
            result.put("status", "SUCCESS");
            result.put("neCount", neCount);
            result.put("linkCount", (long) connRows.size());
            result.put("portCount", (long) ports.size());
            result.put("elapsed", elapsed + "ms");
            result.put("networks", networkNames);
            log.info("同步完成: 网元={}, 链路={}, 端口={}, 耗时={}ms", neCount, connRows.size(), ports.size(), elapsed);

        } catch (Exception e) {
            setConfigValue(KEY_SYNC_STATUS, "FAILED");
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            log.error("同步失败", e);
            throw e;
        } finally {
            mysqlConnectionManager.close();
        }

        return result;
    }

    // ==================== 索引构建 ====================

    /**
     * 一次性构建同步过程中需要的全部查找索引。
     */
    private SyncIndex buildIndex(List<Map<String, Object>> allDmeo,
                                 List<Map<String, Object>> allDmrelation,
                                 List<Map<String, Object>> allDefdmne,
                                 List<Map<String, Object>> allEmnecomm,
                                 List<Map<String, Object>> allPortbandwidth) {
        SyncIndex index = new SyncIndex();

        for (Map<String, Object> row : allDmeo) {
            int cid = toInt(row.get("cid"));
            String oid = toStr(row.get("oid"));
            if (cid == 1) {
                index.networkNameMap.put(oid, toStr(row.get("name")));
            } else if (cid == 2) {
                index.neNameMap.put(oid, toStr(row.get("name")));
                index.neTypeCache.put(oid, toStr(row.get("type")));
            }
            index.portNameMap.put(oid, toStr(row.get("name")));
        }

        // neOid(oid) → networkOid(reo) (dmrelation type=1)
        for (Map<String, Object> row : allDmrelation) {
            if (toInt(row.get("type")) == 1) {
                index.neNetworkMap.put(toStr(row.get("oid")), toStr(row.get("reo")));
            }
        }

        // neType → neTypeName (defdmne)
        for (Map<String, Object> row : allDefdmne) {
            index.neTypeNameMap.put(toStr(row.get("neType")), toStr(row.get("cName")));
        }

        // neOid(oid) → ipAddr (emnecomm state=1)
        for (Map<String, Object> row : allEmnecomm) {
            if (toInt(row.get("state")) == 1) {
                index.ipAddrMap.put(toStr(row.get("oid")), toStr(row.get("ipAddr")));
            }
        }

        // portOid → bandwidth info (portbandwidth；每端口含 dir=1/2/3 三行，固定取 dir=1)
        for (Map<String, Object> row : allPortbandwidth) {
            if (toInt(row.get("dir")) == 1) {
                index.bandwidthMap.put(toStr(row.get("oid")), row);
            }
        }

        return index;
    }

    /**
     * 过滤 dmeo：仅保留目标网络内的对象，并把冗余字段一次性算好。
     */
    private List<Object[]> buildDmeoRows(List<Map<String, Object>> allDmeo,
                                         Set<String> targetNetworks,
                                         SyncIndex index) {
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> row : allDmeo) {
            int cid = toInt(row.get("cid"));
            String oid = toStr(row.get("oid"));
            String networkOid = index.networkOidOf(oid, cid);
            if (networkOid == null || !targetNetworks.contains(networkOid)) {
                continue;
            }
            String neOid = cid == 1 ? null : OidUtil.getNeOid(oid);
            rows.add(new Object[]{
                    oid, cid, row.get("type"), toStr(row.get("name")), toStr(row.get("defName")),
                    networkOid, index.networkNameOf(networkOid),
                    neOid == null ? null : index.neNameMap.get(neOid),
                    index.neTypeNameOf(neOid),
                    neOid == null ? null : index.ipAddrMap.get(neOid)
            });
        }
        return rows;
    }

    /**
     * 过滤 dmconnection：A/Z 任一端 NE 属于目标网络即保留（跨网络链路保留），冗余字段一次性算好。
     */
    private List<Object[]> buildConnectionRows(List<Map<String, Object>> allDmconnection,
                                               Set<String> targetNeOids,
                                               SyncIndex index) {
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> row : allDmconnection) {
            String aEnd = toStr(row.get("aEnd"));
            String zEnd = toStr(row.get("zEnd"));
            String aNeOid = OidUtil.getNeOid(aEnd);
            String zNeOid = OidUtil.getNeOid(zEnd);
            if (!targetNeOids.contains(aNeOid) && !targetNeOids.contains(zNeOid)) {
                continue;
            }
            rows.add(new Object[]{
                    toStr(row.get("oid")), toInt(row.get("cid")), toStr(row.get("name")), aEnd, zEnd,
                    row.get("createTime"), toStr(row.get("creator")), toStr(row.get("additionInfo")),
                    index.neNameMap.get(aNeOid), index.neTypeNameOf(aNeOid),
                    index.networkNameOf(index.neNetworkMap.get(aNeOid)), index.portNameOf(aEnd),
                    index.bandwidthOf(aEnd, "capacity"), index.bandwidthOf(aEnd, "used"),
                    index.neNameMap.get(zNeOid), index.neTypeNameOf(zNeOid),
                    index.networkNameOf(index.neNetworkMap.get(zNeOid)), index.portNameOf(zEnd),
                    index.bandwidthOf(zEnd, "capacity"), index.bandwidthOf(zEnd, "used")
            });
        }
        return rows;
    }

    private void insertRows(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<Object[]> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            sqliteJdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    Object[] row = batch.get(idx);
                    for (int col = 0; col < row.length; col++) {
                        if (row[col] == null) {
                            ps.setNull(col + 1, Types.NULL);
                        } else {
                            ps.setObject(col + 1, row[col]);
                        }
                    }
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
        }
    }

    // ==================== 清除同步数据 ====================

    public Map<String, Object> clearSyncData() {
        Map<String, Object> result = new LinkedHashMap<>();
        sqliteTransactionTemplate.executeWithoutResult(status -> {
            sqliteJdbc.execute("DELETE FROM dmeo");
            sqliteJdbc.execute("DELETE FROM dmconnection");
        });
        setConfigValue(KEY_NETWORK_IDS, "");
        setConfigValue(KEY_NETWORK_NAMES, "");
        setConfigValue(KEY_SYNC_TIME, "");
        setConfigValue(KEY_SYNC_STATUS, "");
        result.put("status", "SUCCESS");
        log.info("同步数据已清除");
        return result;
    }

    // ==================== 辅助方法 ====================

    private static String toStr(Object val) {
        return val != null ? val.toString() : null;
    }

    private static int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 同步过程中使用的只读索引表，避免逐行重复解析 OID。
     */
    private static final class SyncIndex {

        private final Map<String, String> networkNameMap = new HashMap<>();
        private final Map<String, String> neNameMap = new HashMap<>();
        private final Map<String, String> neNetworkMap = new HashMap<>();
        private final Map<String, String> neTypeNameMap = new HashMap<>();
        private final Map<String, String> neTypeCache = new HashMap<>();
        private final Map<String, String> ipAddrMap = new HashMap<>();
        private final Map<String, String> portNameMap = new HashMap<>();
        private final Map<String, Map<String, Object>> bandwidthMap = new HashMap<>();

        /** cid=1 自身即网络，其余通过 dmrelation 反查所属网络 */
        String networkOidOf(String oid, int cid) {
            if (cid == 1) return oid;
            return neNetworkMap.get(OidUtil.getNeOid(oid));
        }

        String networkNameOf(String networkOid) {
            return networkOid == null ? null : networkNameMap.get(networkOid);
        }

        /** neOid → 网元类型中文名 */
        String neTypeNameOf(String neOid) {
            if (neOid == null) return null;
            String type = neTypeCache.get(neOid);
            return type == null ? null : neTypeNameMap.get(type);
        }

        /**
         * 拼接端口名称：dmeo.name + (子架/槽位/端口)
         * 例: "地调1-5.1" + "(1/11/1)" → "地调1-5.1(1/11/1)"
         */
        String portNameOf(String portOid) {
            if (portOid == null || portOid.isEmpty()) return null;
            String baseName = portNameMap.get(portOid);
            if (baseName == null || baseName.isEmpty()) return portOid;

            int[] seg = OidUtil.parseSegments(portOid);
            if (seg.length >= 4) {
                return baseName + "(" + seg[1] + "/" + seg[2] + "/" + seg[3] + ")";
            }
            return baseName;
        }

        int bandwidthOf(String portOid, String field) {
            Map<String, Object> bandwidth = bandwidthMap.get(portOid);
            return bandwidth == null ? 0 : toInt(bandwidth.get(field));
        }

        Set<String> neOidsIn(Set<String> networkOids) {
            Set<String> neOids = new HashSet<>();
            for (Map.Entry<String, String> entry : neNetworkMap.entrySet()) {
                if (networkOids.contains(entry.getValue())) {
                    neOids.add(entry.getKey());
                }
            }
            return neOids;
        }
    }
}
