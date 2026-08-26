package com.optel.qxinspection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 库存统计服务 - 从SQLite查询设备/盘/端口的静态统计数据
 * 数据来源：dmne, defdmne, dmeo, dmrelation（由同步操作写入）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryStatsService {

    @Qualifier("sqliteJdbc")
    private final JdbcTemplate sqliteJdbc;

    private static final int CID_NETWORK = 1;
    private static final int CID_NE = 2;
    private static final int CID_SLOT = 4;
    private static final int CID_PORT = 5;

    /**
     * 获取所有网络名列表（从 dmeo cid=1）
     */
    public List<String> getNetworkNames() {
        return sqliteJdbc.queryForList(
                "SELECT DISTINCT name FROM \"dmeo\" WHERE cid = ? AND name IS NOT NULL AND name != '' ORDER BY name",
                CID_NETWORK
        ).stream().map(row -> (String) row.get("name")).toList();
    }

    /**
     * 总览统计
     */
    public Map<String, Object> getOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        long neCount = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM \"dmne\"", Long.class);
        long slotCount = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM \"dmeo\" WHERE cid = ?", Long.class, CID_SLOT);
        long portCount = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM \"dmeo\" WHERE cid = ?", Long.class, CID_PORT);
        long networkCount = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM \"dmeo\" WHERE cid = ?", Long.class, CID_NETWORK);

        result.put("neCount", neCount);
        result.put("slotCount", slotCount);
        result.put("portCount", portCount);
        result.put("networkCount", networkCount);
        return result;
    }

    /**
     * 网元统计（按设备类型），支持按网络筛选
     */
    public Map<String, Object> getNeStats(String network) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> neTypeMap = buildNeTypeMap();
        Map<String, String> neNetworkMap = buildNeNetworkMap();

        // 查询所有网元
        List<Map<String, Object>> allNe = sqliteJdbc.queryForList("SELECT oid FROM \"dmne\"");

        // 按设备类型分组
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Map<String, Object> ne : allNe) {
            String oid = (String) ne.get("oid");
            // 按网络筛选
            if (network != null && !network.isEmpty()) {
                String netName = neNetworkMap.getOrDefault(oid, "");
                if (!network.equals(netName)) continue;
            }
            String typeName = neTypeMap.getOrDefault(oid, "未知");
            byType.merge(typeName, 1L, Long::sum);
        }
        result.put("byNeTypeName", toSortedList(byType));

        return result;
    }

    /**
     * 获取盘/端口的类型列表（用于筛选下拉框）
     */
    public List<Integer> getObjectTypes(int cid) {
        return sqliteJdbc.queryForList(
                "SELECT DISTINCT type FROM \"dmeo\" WHERE cid = ? AND type IS NOT NULL ORDER BY type",
                cid
        ).stream().map(row -> ((Number) row.get("type")).intValue()).toList();
    }

    /**
     * 盘统计（支持按网络和盘类型筛选）
     */
    public Map<String, Object> getSlotStats(String network, Integer objectType) {
        return getDmeoStats(CID_SLOT, network, objectType);
    }

    /**
     * 端口统计（支持按网络和端口类型筛选）
     */
    public Map<String, Object> getPortStats(String network, Integer objectType) {
        return getDmeoStats(CID_PORT, network, objectType);
    }

    private Map<String, Object> getDmeoStats(int cid, String network, Integer objectType) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> neNetworkMap = buildNeNetworkMap();

        List<Map<String, Object>> allDmeo = sqliteJdbc.queryForList(
                "SELECT oid, type FROM \"dmeo\" WHERE cid = ?", cid);

        Map<String, Long> byType = new LinkedHashMap<>();
        for (Map<String, Object> d : allDmeo) {
            String oid = (String) d.get("oid");
            String neOid = extractNeOid(oid);
            String networkName = neNetworkMap.getOrDefault(neOid, "未分配");

            if (network != null && !network.isEmpty() && !network.equals(networkName)) continue;
            Integer type = d.get("type") != null ? ((Number) d.get("type")).intValue() : null;
            if (objectType != null && !objectType.equals(type)) continue;

            String typeName = d.get("type") != null ? String.valueOf(d.get("type")) : "未知";
            byType.merge(typeName, 1L, Long::sum);
        }

        result.put("byTypeName", toSortedList(byType));
        return result;
    }

    private String extractNeOid(String dmeoOid) {
        if (dmeoOid == null) return "";
        int firstColon = dmeoOid.indexOf(':');
        if (firstColon < 0) return dmeoOid;
        return dmeoOid.substring(0, firstColon);
    }

    /**
     * 构建 neOid → 设备类型名 映射
     */
    private Map<String, String> buildNeTypeMap() {
        // 从 defdmne 加载类型定义
        Map<Integer, String> typeDefMap = sqliteJdbc.queryForList("SELECT \"neType\", \"cName\", \"eName\" FROM \"defdmne\"")
                .stream().collect(Collectors.toMap(
                        row -> ((Number) row.get("neType")).intValue(),
                        row -> row.get("cName") != null ? (String) row.get("cName") : (String) row.get("eName"),
                        (a, b) -> a));

        // 从 dmne 加载所有网元
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> ne : sqliteJdbc.queryForList("SELECT oid, type FROM \"dmne\"")) {
            String oid = (String) ne.get("oid");
            Integer type = ne.get("type") != null ? ((Number) ne.get("type")).intValue() : null;
            String typeName = type != null
                    ? typeDefMap.getOrDefault(type, "未知(" + type + ")")
                    : "未知";
            result.put(oid, typeName);
        }
        return result;
    }

    /**
     * 构建 neOid → 网络名 映射（通过 dmrelation + dmeo cid=1）
     */
    private Map<String, String> buildNeNetworkMap() {
        // 从 dmeo cid=1 获取网络实例名称
        Map<String, String> netNameMap = new HashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList(
                "SELECT oid, name FROM \"dmeo\" WHERE cid = ?", CID_NETWORK)) {
            String oid = (String) row.get("oid");
            String name = row.get("name") != null ? (String) row.get("name") : oid;
            netNameMap.put(oid, name);
        }

        // 从 dmrelation 获取 NE→网络的归属关系（type=1）
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList(
                "SELECT oid, reo FROM \"dmrelation\" WHERE type = 1")) {
            String neOid = (String) row.get("oid");
            String netOid = (String) row.get("reo");
            String networkName = netNameMap.getOrDefault(netOid, netOid);
            result.put(neOid, networkName);
        }
        return result;
    }

    private List<Map<String, Object>> toSortedList(Map<String, Long> map) {
        List<Map<String, Object>> list = new ArrayList<>();
        map.forEach((key, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", key);
            entry.put("count", value);
            list.add(entry);
        });
        list.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return list;
    }
}
