package com.optel.qxinspection.service;

import com.optel.qxinspection.util.OidUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 端口带宽利用率查询服务。
 * <p>
 * 数据来源均为 SQLite 本地库：
 * <ul>
 *   <li>portbandwidth — EMS 同步的端口带宽数据</li>
 *   <li>dmeo — 端口定义表，用于按 defName 过滤</li>
 *   <li>optical_power_inspection — 光功率巡检记录</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class BandwidthService {

    private final JdbcTemplate sqliteJdbc;

    @Value("${app.inspection.bandwidth-defname-patterns:STM%,1G_LAN%,10GE%,40GE%,100GE%}")
    private String bandwidthDefnamePatterns;

    /** 最大模式数，与光功率过滤保持一致 */
    private static final int MAX_PATTERNS = 5;

    /** 方向常量：发送方向 */
    private static final int DIR_TX = 1;

    /** dmeo cid 常量：网元实例 */
    private static final int CID_NE = 2;

    public BandwidthService(@Qualifier("sqliteDataSource") javax.sql.DataSource sqliteDs) {
        this.sqliteJdbc = new JdbcTemplate(sqliteDs);
    }

    // ==================== 公共 API ====================

    /**
     * 查询指定网元的端口带宽（含光功率合并数据）。
     *
     * @param neId 网元ID
     * @return 合并后的端口带宽列表
     */
    public List<Map<String, Object>> queryByNe(String neId) {
        // 1. 按带宽过滤模式查 dmeo，获取该网元下需要展示带宽的端口
        List<Map<String, Object>> filteredPorts = queryFilteredPorts(neId);

        // 2. 查 portbandwidth（该网元所有带宽数据）
        Map<String, Map<String, Object>> bwMap = queryBandwidthByNe(neId);

        // 3. 查 optical_power_inspection（该网元所有光功率数据）
        Map<String, Map<String, Object>> powerMap = queryOpticalPowerByNe(neId);

        // 4. 查网元名称映射
        Map<String, String> neNameMap = queryNeNameMap(neId);

        // 5. 合并结果
        return mergePorts(filteredPorts, bwMap, powerMap, neNameMap);
    }

    /**
     * 查询全网端口带宽概览。
     *
     * @return 合并后的端口带宽列表
     */
    public List<Map<String, Object>> queryAll() {
        // 1. 按带宽过滤模式查 dmeo，获取全网需要展示带宽的端口
        List<Map<String, Object>> filteredPorts = queryFilteredPorts(null);

        // 2. 查全网 portbandwidth
        Map<String, Map<String, Object>> bwMap = queryBandwidthAll();

        // 3. 查全网 optical_power_inspection
        Map<String, Map<String, Object>> powerMap = queryOpticalPowerAll();

        // 4. 查网元名称映射
        Map<String, String> neNameMap = queryNeNameMap(null);

        // 5. 合并结果
        return mergePorts(filteredPorts, bwMap, powerMap, neNameMap);
    }

    /**
     * 查询带宽汇总统计。
     *
     * @param neId 网元ID（可选，null 表示全网）
     * @return 汇总统计
     */
    public Map<String, Object> getSummary(String neId) {
        List<Map<String, Object>> data = (neId != null && !neId.isEmpty())
                ? queryByNe(neId) : queryAll();

        long totalPorts = data.size();
        long withBw = data.stream().filter(d -> d.get("capacity") != null).count();
        long withPower = data.stream().filter(d -> d.get("txPower") != null).count();
        long highUsage = data.stream()
                .filter(d -> d.get("usageRate") != null)
                .filter(d -> ((Number) d.get("usageRate")).doubleValue() >= 80.0)
                .count();
        long fullLoad = data.stream()
                .filter(d -> d.get("usageRate") != null)
                .filter(d -> ((Number) d.get("usageRate")).doubleValue() >= 100.0)
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPorts", totalPorts);
        summary.put("withBandwidth", withBw);
        summary.put("withOpticalPower", withPower);
        summary.put("highUsage", highUsage);
        summary.put("fullLoad", fullLoad);
        return summary;
    }

    // ==================== 内部查询方法 ====================

    /**
     * 按带宽过滤模式查询 dmeo 中符合条件的端口。
     * <p>过滤条件：cid=5（端口类）且 defName LIKE 匹配配置的模式</p>
     *
     * @param neId 网元ID（可选，null 表示全网）
     * @return 符合条件的端口列表
     */
    private List<Map<String, Object>> queryFilteredPorts(String neId) {
        String[] patterns = parsePatterns();
        List<String> likeConditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (String p : patterns) {
            if (p != null) {
                likeConditions.add("defName LIKE ?");
                params.add(p);
            }
        }
        if (likeConditions.isEmpty()) {
            return List.of();
        }

        StringBuilder where = new StringBuilder("cid = 5 AND (");
        where.append(String.join(" OR ", likeConditions));
        where.append(")");

        if (neId != null && !neId.isEmpty()) {
            where.append(" AND oid LIKE ? || ':%'");
            params.add(neId);
        }

        String sql = "SELECT * FROM \"dmeo\" WHERE " + where;
        log.debug("queryFilteredPorts: neId={}, patterns={}", neId, Arrays.toString(patterns));
        return sqliteJdbc.queryForList(sql, params.toArray());
    }

    /**
     * 查询指定网元的端口带宽数据。
     * <p>只取 dir=1（发送方向）避免重复，按 oid 分组</p>
     *
     * @param neId 网元ID
     * @return key=neId:slotNo:portNo, value=带宽数据
     */
    private Map<String, Map<String, Object>> queryBandwidthByNe(String neId) {
        String sql = "SELECT * FROM \"portbandwidth\" WHERE oid LIKE ? || ':%'";
        List<Map<String, Object>> rows = sqliteJdbc.queryForList(sql, neId);
        return indexBandwidthRows(rows);
    }

    /**
     * 查询全网端口带宽数据。
     *
     * @return key=neId:slotNo:portNo, value=带宽数据
     */
    private Map<String, Map<String, Object>> queryBandwidthAll() {
        String sql = "SELECT * FROM \"portbandwidth\"";
        List<Map<String, Object>> rows = sqliteJdbc.queryForList(sql);
        return indexBandwidthRows(rows);
    }

    /**
     * 将 portbandwidth 行按端口 key 索引，取 dir=1 的记录。
     * <p>portbandwidth.oid 格式为 "neId:slotNo:portNo:x"，取前 3 段作为 key</p>
     *
     * @param rows 原始查询结果
     * @return key=neId:slotNo:portNo, value=带宽数据
     */
    private Map<String, Map<String, Object>> indexBandwidthRows(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int dir = row.get("dir") != null ? ((Number) row.get("dir")).intValue() : 0;
            if (dir != DIR_TX) {
                continue;
            }
            String oid = (String) row.get("oid");
            String portKey = extractPortKey(oid);
            if (portKey != null) {
                map.put(portKey, row);
            }
        }
        return map;
    }

    /**
     * 从 portbandwidth 的 oid 中提取端口标识 key（neId:slotNo:portNo）。
     *
     * @param oid portbandwidth 的 oid，格式 "neId:slotNo:portNo:x"
     * @return neId:slotNo:portNo 格式的 key，解析失败返回 null
     */
    private String extractPortKey(String oid) {
        if (oid == null || oid.isEmpty()) {
            return null;
        }
        int[] seg = OidUtil.parseSegments(oid);
        if (seg.length < 3) {
            return null;
        }
        return seg[0] + ":" + seg[1] + ":" + seg[2];
    }

    /**
     * 查询指定网元的光功率巡检数据。
     *
     * @param neId 网元ID
     * @return key=neId:slotNo:portNo, value=光功率数据
     */
    private Map<String, Map<String, Object>> queryOpticalPowerByNe(String neId) {
        String sql = "SELECT * FROM \"optical_power_inspection\" " +
                "WHERE ne_id = ? AND supported = 1 " +
                "ORDER BY round_id DESC";
        List<Map<String, Object>> rows = sqliteJdbc.queryForList(sql, neId);
        return indexOpticalPowerRows(rows);
    }

    /**
     * 查询全网光功率巡检数据（每个端口只取最新一条）。
     *
     * @return key=neId:slotNo:portNo, value=光功率数据
     */
    private Map<String, Map<String, Object>> queryOpticalPowerAll() {
        // 子查询获取每个端口最新的 round_id
        String sql = "SELECT o.* FROM \"optical_power_inspection\" o " +
                "INNER JOIN (" +
                "  SELECT ne_id, slot_no, port_no, MAX(round_id) AS max_round " +
                "  FROM \"optical_power_inspection\" " +
                "  WHERE supported = 1 " +
                "  GROUP BY ne_id, slot_no, port_no" +
                ") latest ON o.ne_id = latest.ne_id " +
                "  AND o.slot_no = latest.slot_no " +
                "  AND o.port_no = latest.port_no " +
                "  AND o.round_id = latest.max_round";
        List<Map<String, Object>> rows = sqliteJdbc.queryForList(sql);
        return indexOpticalPowerRows(rows);
    }

    /**
     * 将光功率巡检行按端口 key 索引。
     *
     * @param rows 原始查询结果
     * @return key=neId:slotNo:portNo, value=光功率数据
     */
    private Map<String, Map<String, Object>> indexOpticalPowerRows(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String neId = (String) row.get("ne_id");
            Object slotNoObj = row.get("slot_no");
            Object portNoObj = row.get("port_no");
            if (neId == null || slotNoObj == null || portNoObj == null) {
                continue;
            }
            String key = neId + ":" + slotNoObj + ":" + portNoObj;
            // 已有记录则跳过（取最新的）
            map.putIfAbsent(key, row);
        }
        return map;
    }

    /**
     * 查询网元名称映射（优先从 device_access_config，回退到 dmeo cid=2）。
     *
     * @param neId 网元ID（可选，null 表示全网）
     * @return key=neId, value=neName
     */
    private Map<String, String> queryNeNameMap(String neId) {
        Map<String, String> map = new HashMap<>();

        // 优先从 device_access_config 获取（该表始终存在）
        String sql;
        List<Object> params = new ArrayList<>();
        if (neId != null && !neId.isEmpty()) {
            sql = "SELECT ne_id, ne_name FROM \"device_access_config\" WHERE ne_id = ?";
            params.add(neId);
        } else {
            sql = "SELECT ne_id, ne_name FROM \"device_access_config\"";
        }
        try {
            for (Map<String, Object> row : sqliteJdbc.queryForList(sql, params.toArray())) {
                String id = (String) row.get("ne_id");
                String name = row.get("ne_name") != null ? (String) row.get("ne_name") : "";
                if (id != null && !name.isEmpty()) {
                    map.put(id, name);
                }
            }
        } catch (Exception e) {
            log.debug("查询 device_access_config 失败: {}", e.getMessage());
        }

        // 回退到 dmeo cid=2（补充 device_access_config 中没有的网元）
        if (map.isEmpty()) {
            List<Object> dmeoParams = new ArrayList<>();
            String dmeoSql;
            if (neId != null && !neId.isEmpty()) {
                dmeoSql = "SELECT oid, name FROM \"dmeo\" WHERE cid = ? AND oid = ?";
                dmeoParams.add(CID_NE);
                dmeoParams.add(neId);
            } else {
                dmeoSql = "SELECT oid, name FROM \"dmeo\" WHERE cid = ?";
                dmeoParams.add(CID_NE);
            }
            try {
                for (Map<String, Object> row : sqliteJdbc.queryForList(dmeoSql, dmeoParams.toArray())) {
                    String oid = (String) row.get("oid");
                    String name = row.get("name") != null ? (String) row.get("name") : "";
                    if (oid != null && !name.isEmpty()) {
                        map.put(oid, name);
                    }
                }
            } catch (Exception e) {
                log.debug("查询 dmeo cid=2 失败: {}", e.getMessage());
            }
        }

        return map;
    }

    // ==================== 合并逻辑 ====================

    /**
     * 合并端口列表、带宽数据、光功率数据。
     * <p>
     * 三类端口处理：
     * <ul>
     *   <li>A类：有光功率 + 有带宽 → 完整展示</li>
     *   <li>B类：有光功率 + 无带宽 → 带宽显示"—"</li>
     *   <li>C类：无光功率 + 有带宽 → 光功率显示"非光口"</li>
     * </ul>
     * </p>
     *
     * @param filteredPorts dmeo 中符合过滤条件的端口列表
     * @param bwMap 带宽数据索引
     * @param powerMap 光功率数据索引
     * @param neNameMap 网元名称映射
     * @return 合并后的端口带宽列表
     */
    private List<Map<String, Object>> mergePorts(List<Map<String, Object>> filteredPorts,
                                                  Map<String, Map<String, Object>> bwMap,
                                                  Map<String, Map<String, Object>> powerMap,
                                                  Map<String, String> neNameMap) {
        // 收集所有需要展示的端口 key（过滤端口 ∪ 有带宽的端口）
        Set<String> allKeys = new LinkedHashSet<>();

        // 从过滤端口添加
        for (Map<String, Object> port : filteredPorts) {
            String key = buildPortKey(port);
            if (key != null) {
                allKeys.add(key);
            }
        }

        // 从带宽数据添加（覆盖有带宽但不在过滤列表中的端口）
        allKeys.addAll(bwMap.keySet());

        // 构建结果列表
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : allKeys) {
            Map<String, Object> merged = new LinkedHashMap<>();

            // 解析端口 key
            String[] parts = key.split(":");
            String neId = parts.length > 0 ? parts[0] : "";
            int slotNo = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int portNo = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

            merged.put("neId", neId);
            merged.put("slotNo", slotNo);
            merged.put("portNo", portNo);

            // 带宽数据
            Map<String, Object> bwData = bwMap.get(key);
            if (bwData != null) {
                fillBandwidthData(merged, bwData);
            } else {
                merged.put("hasBandwidth", false);
            }

            // 光功率数据
            Map<String, Object> powerData = powerMap.get(key);
            if (powerData != null) {
                fillOpticalPowerData(merged, powerData);
            } else {
                merged.put("hasOpticalPower", false);
            }

            // 网元名称兜底：如果光功率未填充，则从 dmeo 查询补充
            if (merged.get("neName") == null || merged.get("neName").toString().isEmpty()) {
                merged.put("neName", neNameMap.getOrDefault(neId, ""));
            }

            result.add(merged);
        }

        // 按网元ID + 槽位 + 端口排序
        result.sort(Comparator
                .comparing((Map<String, Object> m) -> (String) m.get("neId"))
                .thenComparingInt(m -> (int) m.get("slotNo"))
                .thenComparingInt(m -> (int) m.get("portNo")));

        return result;
    }

    /**
     * 从 dmeo 行构建端口 key。
     *
     * @param port dmeo 行数据
     * @return neId:slotNo:portNo 格式的 key
     */
    private String buildPortKey(Map<String, Object> port) {
        String oid = (String) port.get("oid");
        if (oid == null) {
            return null;
        }
        int[] seg = OidUtil.parseSegments(oid);
        if (seg.length < 4) {
            return null;
        }
        // dmeo oid 格式: neId:subrack:slot:port → 取 neId, slot(第3段), port(第4段)
        return seg[0] + ":" + seg[2] + ":" + seg[3];
    }

    /**
     * 填充带宽数据到合并结果中。
     *
     * @param merged 合并结果
     * @param bwData 带宽原始数据
     */
    private void fillBandwidthData(Map<String, Object> merged, Map<String, Object> bwData) {
        merged.put("hasBandwidth", true);

        Number capacityNum = (Number) bwData.get("capacity");
        Number usedNum = (Number) bwData.get("used");
        int capacity = capacityNum != null ? capacityNum.intValue() : 0;
        int used = usedNum != null ? usedNum.intValue() : 0;

        merged.put("capacity", capacity);
        merged.put("used", used);

        // 利用率百分比
        double usageRate = capacity > 0 ? (used * 100.0 / capacity) : 0;
        merged.put("usageRate", Math.round(usageRate * 10.0) / 10.0);

        // 解码通道位图
        String vc4Map = (String) bwData.get("vc4chMap");
        String vc3Map = (String) bwData.get("vc3chMap");
        String vc12Map = (String) bwData.get("vc12chMap");

        merged.put("vc4", decodeChannelMap(vc4Map, 4));
        merged.put("vc3", decodeChannelMap(vc3Map, 3));
        merged.put("vc12", decodeChannelMap(vc12Map, 12));
    }

    /**
     * 填充光功率数据到合并结果中。
     *
     * @param merged 合并结果
     * @param powerData 光功率原始数据
     */
    private void fillOpticalPowerData(Map<String, Object> merged, Map<String, Object> powerData) {
        merged.put("hasOpticalPower", true);
        merged.put("neName", powerData.get("ne_name"));
        merged.put("networkName", powerData.get("network_name"));
        merged.put("neTypeName", powerData.get("ne_type_name"));
        merged.put("portName", powerData.get("port_name"));
        merged.put("portType", powerData.get("port_type"));
        merged.put("laserState", powerData.get("laser_state"));
        merged.put("laserType", powerData.get("laser_type"));
        merged.put("laserDistance", powerData.get("laser_distance"));
        merged.put("moduleTypeKey", powerData.get("module_type_key"));
        merged.put("vendorName", powerData.get("vendor_name"));
        merged.put("txPower", powerData.get("tx_power"));
        merged.put("rxPower", powerData.get("rx_power"));
        merged.put("txPowerStatus", powerData.get("tx_power_status"));
        merged.put("rxPowerStatus", powerData.get("rx_power_status"));
        merged.put("txLowThreshold", powerData.get("tx_low_threshold"));
        merged.put("txHighThreshold", powerData.get("tx_high_threshold"));
        merged.put("lowThreshold", powerData.get("low_threshold"));
        merged.put("highThreshold", powerData.get("high_threshold"));
        merged.put("inspectionTime", powerData.get("inspection_time"));
    }

    // ==================== 位图解码 ====================

    /**
     * 解码通道位图。
     * <p>
     * 十六进制字符串，每 2 位表示一个字节，每个 bit 表示一个通道的使用状态。
     * bit=1 表示通道已占用。
     * </p>
     *
     * @param hexMap 十六进制位图字符串
     * @param vcLevel VC 等级（4、3、12）
     * @return 解码后的通道信息
     */
    private Map<String, Object> decodeChannelMap(String hexMap, int vcLevel) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (hexMap == null || hexMap.isEmpty()) {
            info.put("totalCount", 0);
            info.put("usedCount", 0);
            info.put("freeCount", 0);
            info.put("channels", List.of());
            return info;
        }

        // 十六进制转二进制位数组
        List<Boolean> bits = new ArrayList<>();
        for (int i = 0; i < hexMap.length(); i++) {
            int val = Character.digit(hexMap.charAt(i), 16);
            for (int bit = 3; bit >= 0; bit--) {
                bits.add(((val >> bit) & 1) == 1);
            }
        }

        int totalCount = bits.size();
        int usedCount = 0;
        List<Integer> usedChannels = new ArrayList<>();
        List<Integer> freeChannels = new ArrayList<>();

        for (int i = 0; i < bits.size(); i++) {
            if (bits.get(i)) {
                usedCount++;
                usedChannels.add(i + 1);
            } else {
                freeChannels.add(i + 1);
            }
        }

        info.put("vcLevel", vcLevel);
        info.put("totalCount", totalCount);
        info.put("usedCount", usedCount);
        info.put("freeCount", totalCount - usedCount);
        info.put("usedChannels", usedChannels);
        info.put("freeChannels", freeChannels);
        return info;
    }

    // ==================== 工具方法 ====================

    /**
     * 解析带宽过滤模式配置。
     *
     * @return 过滤模式数组（固定长度 MAX_PATTERNS，空位为 null）
     */
    private String[] parsePatterns() {
        String[] raw = bandwidthDefnamePatterns.split(",");
        String[] result = new String[MAX_PATTERNS];
        for (int i = 0; i < MAX_PATTERNS; i++) {
            result[i] = i < raw.length ? raw[i].trim() : null;
        }
        return result;
    }
}
