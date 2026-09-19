package com.optel.qxinspection.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存统计服务 —— 全部统计口径均来自 SQLite 的 dmeo / dmconnection 同步表。
 */
@Service
@RequiredArgsConstructor
public class InventoryStatsService {

    /** dmeo cid：网络 */
    public static final int CID_NETWORK = 1;
    /** dmeo cid：网元 */
    public static final int CID_NE = 2;
    /** dmeo cid：盘 */
    public static final int CID_SLOT = 4;
    /** dmeo cid：端口 */
    public static final int CID_PORT = 5;
    /** dmconnection cid：链路 */
    public static final int CID_LINK = 100;

    private final JdbcTemplate sqliteJdbc;

    /**
     * 获取所有网络名列表（dmeo cid=1）
     */
    public List<String> getNetworkNames() {
        return sqliteJdbc.queryForList(
                "SELECT name FROM dmeo WHERE cid = ? AND name IS NOT NULL AND name != '' ORDER BY name",
                String.class, CID_NETWORK);
    }

    /**
     * 总览统计：网络 / 网元 / 盘 / 端口 / 链路数量
     */
    public Map<String, Object> getOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("networkCount", countByCid(CID_NETWORK));
        result.put("neCount", countByCid(CID_NE));
        result.put("slotCount", countByCid(CID_SLOT));
        result.put("portCount", countByCid(CID_PORT));
        Long linkCount = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM dmconnection WHERE cid = ?", Long.class, CID_LINK);
        result.put("linkCount", linkCount != null ? linkCount : 0L);
        return result;
    }

    private long countByCid(int cid) {
        Long value = sqliteJdbc.queryForObject("SELECT COUNT(*) FROM dmeo WHERE cid = ?", Long.class, cid);
        return value != null ? value : 0L;
    }

    /**
     * 网元类型统计（dmeo cid=2），支持按网络筛选
     */
    public Map<String, Object> getNeStats(String network) {
        List<Object> params = new ArrayList<>();
        params.add(CID_NE);
        StringBuilder sql = new StringBuilder("SELECT neTypeName FROM dmeo WHERE cid = ?");
        appendNetworkFilter(sql, params, network);

        Map<String, Long> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList(sql.toString(), params.toArray())) {
            byType.merge(labelOf(row.get("neTypeName")), 1L, Long::sum);
        }
        return Map.of("byNeTypeName", toSortedList(byType));
    }

    /**
     * 盘类型统计（dmeo cid=4），支持按网络筛选
     */
    public Map<String, Object> getSlotStats(String network) {
        return getDmeoStats(CID_SLOT, network);
    }

    /**
     * 端口类型统计（dmeo cid=5），支持按网络筛选
     */
    public Map<String, Object> getPortStats(String network) {
        return getDmeoStats(CID_PORT, network);
    }

    private Map<String, Object> getDmeoStats(int cid, String network) {
        List<Object> params = new ArrayList<>();
        params.add(cid);
        StringBuilder sql = new StringBuilder("SELECT type FROM dmeo WHERE cid = ?");
        appendNetworkFilter(sql, params, network);

        Map<String, Long> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList(sql.toString(), params.toArray())) {
            byType.merge(labelOf(row.get("type")), 1L, Long::sum);
        }
        return Map.of("byTypeName", toSortedList(byType));
    }

    private static void appendNetworkFilter(StringBuilder sql, List<Object> params, String network) {
        if (network != null && !network.isEmpty()) {
            sql.append(" AND networkName = ?");
            params.add(network);
        }
    }

    private static String labelOf(Object value) {
        return value != null ? value.toString() : "未知";
    }

    /**
     * 按数量倒序输出 [{name, count, percent}]，percent 为占比（保留 1 位小数）。
     */
    private static List<Map<String, Object>> toSortedList(Map<String, Long> map) {
        long total = map.values().stream().mapToLong(Long::longValue).sum();
        List<Map<String, Object>> list = new ArrayList<>();
        map.forEach((key, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", key);
            entry.put("count", value);
            entry.put("percent", total > 0 ? Math.round(value * 1000.0 / total) / 10.0 : 0.0);
            list.add(entry);
        });
        list.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return list;
    }
}
