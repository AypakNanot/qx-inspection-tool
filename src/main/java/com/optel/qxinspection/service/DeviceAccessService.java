package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.sqlite.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeviceAccessService {

    private final JdbcTemplate sqliteJdbc;
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;
    private final ConnProfileRepository connProfileRepository;
    private final InspectionRoundRepository inspectionRoundRepository;
    private final OpticalPowerInspectionRepository opticalPowerInspectionRepository;
    private final ThresholdRuleRepository thresholdRuleRepository;

    public DeviceAccessService(@Qualifier("sqliteDataSource") DataSource sqliteDs,
                               DeviceAccessConfigRepository deviceAccessConfigRepository,
                               ConnProfileRepository connProfileRepository,
                               InspectionRoundRepository inspectionRoundRepository,
                               OpticalPowerInspectionRepository opticalPowerInspectionRepository,
                               ThresholdRuleRepository thresholdRuleRepository) {
        this.sqliteJdbc = new JdbcTemplate(sqliteDs);
        this.deviceAccessConfigRepository = deviceAccessConfigRepository;
        this.connProfileRepository = connProfileRepository;
        this.inspectionRoundRepository = inspectionRoundRepository;
        this.opticalPowerInspectionRepository = opticalPowerInspectionRepository;
        this.thresholdRuleRepository = thresholdRuleRepository;
    }

    /**
     * 从SQLite已同步的表中生成设备配置
     * 依赖维护页先同步: dmne, defdmne, dmeo, dmrelation, emnecomm
     * @param networkFilter 网络名称筛选，为空则同步全部
     */
    @Transactional(transactionManager = "sqliteTransactionManager")
    public void syncDevicesFromSQLite(String networkFilter) {
        log.info("开始从SQLite同步表生成设备配置, 网络筛选: {}", networkFilter == null ? "全部" : networkFilter);

        // 检查必要的同步表是否存在
        if (!isTableExists("dmne") || !isTableExists("emnecomm")) {
            throw new IllegalStateException("设备数据尚未同步，请先在「维护 → 数据维护」页面执行同步");
        }

        // 查询所有设备
        List<Map<String, Object>> allDevices = sqliteJdbc.queryForList("SELECT oid, type FROM \"dmne\"");
        log.info("查询到{}台设备", allDevices.size());

        // 预加载设备类型名称映射
        Map<Integer, Map<String, Object>> typeMap = sqliteJdbc.queryForList("SELECT neType, cName, eName FROM \"defdmne\"")
                .stream().collect(Collectors.toMap(
                        row -> ((Number) row.get("neType")).intValue(),
                        row -> row, (a, b) -> a));

        // 从 dmeo 表（cid=2）加载网元实例名称
        Map<String, String> neNameMap = new HashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList("SELECT oid, name FROM \"dmeo\" WHERE cid = 2")) {
            neNameMap.put((String) row.get("oid"),
                    row.get("name") != null ? (String) row.get("name") : "");
        }

        // 批量查询活跃通信配置（state=1）
        List<String> allOids = allDevices.stream().map(d -> (String) d.get("oid")).toList();
        Map<String, String> commMap = new HashMap<>();
        if (!allOids.isEmpty()) {
            int batchSize = 500;
            for (int i = 0; i < allOids.size(); i += batchSize) {
                List<String> batch = allOids.subList(i, Math.min(i + batchSize, allOids.size()));
                String placeholders = batch.stream().map(o -> "?").collect(Collectors.joining(","));
                List<Map<String, Object>> comms = sqliteJdbc.queryForList(
                        "SELECT oid, ipAddr FROM \"emnecomm\" WHERE oid IN (" + placeholders + ") AND state = 1",
                        batch.toArray());
                for (Map<String, Object> c : comms) {
                    commMap.put((String) c.get("oid"), (String) c.get("ipAddr"));
                }
            }
        }

        // 批量查询网络归属（type=1 为归属关系）
        Map<String, String> netNameMap = buildNetworkNameMap(allOids);

        // 预加载已有配置
        Map<String, DeviceAccessConfig> existingMap = deviceAccessConfigRepository.findAll().stream()
                .collect(Collectors.toMap(DeviceAccessConfig::getNeId, c -> c));

        List<DeviceAccessConfig> toSave = new ArrayList<>();
        int syncCount = 0;
        int skipCount = 0;
        for (Map<String, Object> dmNe : allDevices) {
            String oid = (String) dmNe.get("oid");
            String ipAddr = commMap.get(oid);
            Integer type = dmNe.get("type") != null ? ((Number) dmNe.get("type")).intValue() : null;

            // 跳过没有活跃IP的设备
            if (ipAddr == null || "0.0.0.0".equals(ipAddr)) continue;

            Map<String, Object> typeInfo = type != null ? typeMap.get(type) : null;
            String neName = neNameMap.getOrDefault(oid,
                    typeInfo != null ? (String) typeInfo.get("cName") : "Unknown");
            String typeName = typeInfo != null ? (String) typeInfo.get("eName") :
                    (type != null ? String.valueOf(type) : "Unknown");
            String networkName = netNameMap.getOrDefault(oid, "");

            // 按网络筛选
            if (networkFilter != null && !networkFilter.isEmpty()
                    && !networkFilter.equals(networkName)) {
                skipCount++;
                continue;
            }

            DeviceAccessConfig existing = existingMap.get(oid);
            if (existing != null) {
                existing.setNeName(neName);
                existing.setNeTypeName(typeName);
                existing.setNetworkName(networkName);
                existing.setIpAddr(ipAddr);
                toSave.add(existing);
            } else {
                DeviceAccessConfig config = new DeviceAccessConfig();
                config.setNeId(oid);
                config.setNeName(neName);
                config.setNeTypeName(typeName);
                config.setNetworkName(networkName);
                config.setIpAddr(ipAddr);
                config.setEnabled(true);
                config.setConnectionStatus(0);
                toSave.add(config);
                syncCount++;
            }
        }

        deviceAccessConfigRepository.saveAll(toSave);
        log.info("同步完成，更新/新增{}台设备配置，跳过{}台", toSave.size(), skipCount);
    }

    /**
     * 构建网元oid → 网络名称映射
     */
    private Map<String, String> buildNetworkNameMap(List<String> oids) {
        // 查询所有归属关系（type=1）
        List<Map<String, Object>> relations = sqliteJdbc.queryForList(
                "SELECT oid, reo FROM \"dmrelation\" WHERE type = 1");

        // 筛选涉及当前网元的关系
        Set<String> oidSet = new HashSet<>(oids);
        List<String> netOids = relations.stream()
                .filter(r -> oidSet.contains((String) r.get("oid")))
                .map(r -> (String) r.get("reo"))
                .filter(reo -> reo != null && !reo.isEmpty())
                .distinct()
                .toList();

        if (netOids.isEmpty()) return Map.of();

        // 从 dmeo 表（cid=1）获取网络实例名称
        Map<String, String> netNameMap = new HashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList("SELECT oid, name FROM \"dmeo\" WHERE cid = 1")) {
            String oid = (String) row.get("oid");
            netNameMap.put(oid, row.get("name") != null ? (String) row.get("name") : oid);
        }

        // 构建 网元oid → 网络名称
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> rel : relations) {
            if (!oidSet.contains((String) rel.get("oid"))) continue;
            String netOid = (String) rel.get("reo");
            result.put((String) rel.get("oid"), netNameMap.getOrDefault(netOid, netOid));
        }
        return result;
    }

    public List<DeviceAccessConfig> getAllDeviceConfigs() {
        return deviceAccessConfigRepository.findAll();
    }

    public List<DeviceAccessConfig> getEnabledDeviceConfigs() {
        return deviceAccessConfigRepository.findByEnabledTrue();
    }

    /**
     * 选择性清除本地SQLite数据
     */
    @Transactional(transactionManager = "sqliteTransactionManager")
    public Map<String, Long> clearSelectedData(Map<String, Object> options) {
        Map<String, Long> counts = new LinkedHashMap<>();

        boolean clearRecords = Boolean.TRUE.equals(options.get("inspectionRecords"));
        boolean clearRounds = Boolean.TRUE.equals(options.get("inspectionRounds"));
        boolean clearDevices = Boolean.TRUE.equals(options.get("deviceConfigs"));
        boolean clearThreshold = Boolean.TRUE.equals(options.get("thresholdRules"));
        Object connOpt = options.get("connectionProfiles");

        if (clearRecords) {
            long c = opticalPowerInspectionRepository.count();
            counts.put("巡检记录", c);
            opticalPowerInspectionRepository.deleteAllInBatch();
        }
        if (clearRounds) {
            long c = inspectionRoundRepository.count();
            counts.put("巡检轮次", c);
            inspectionRoundRepository.deleteAllInBatch();
        }

        if (connOpt != null) {
            log.info("清除连接配置, connOpt type={}, value={}", connOpt.getClass().getName(), connOpt);
            if ("all".equals(connOpt)) {
                long c = connProfileRepository.count();
                counts.put("连接配置", c);
                connProfileRepository.deleteAllInBatch();
            } else if (connOpt instanceof List<?> networks && !networks.isEmpty()) {
                List<String> networkNames = networks.stream().map(Object::toString).toList();
                log.info("按网络清除连接配置, networks={}", networkNames);
                List<String> neIds = deviceAccessConfigRepository.findAll().stream()
                        .filter(d -> d.getNetworkName() != null && networkNames.contains(d.getNetworkName()))
                        .map(DeviceAccessConfig::getNeId)
                        .toList();
                log.info("匹配到{}台设备", neIds.size());
                if (!neIds.isEmpty()) {
                    List<ConnProfile> allProfiles = connProfileRepository.findAll();
                    List<ConnProfile> toDelete = allProfiles.stream()
                            .filter(p -> "NE".equals(p.getScope()) && neIds.contains(p.getNeOid()))
                            .toList();
                    long c = toDelete.size();
                    counts.put("连接配置(" + String.join(",", networkNames) + ")", c);
                    connProfileRepository.deleteAll(toDelete);
                    log.info("已删除{}条连接配置", c);
                }
            }
        }

        if (clearDevices) {
            long c = deviceAccessConfigRepository.count();
            counts.put("设备配置", c);
            deviceAccessConfigRepository.deleteAllInBatch();
        }
        if (clearThreshold) {
            long c = thresholdRuleRepository.count();
            counts.put("门限规则", c);
            thresholdRuleRepository.deleteAllInBatch();
        }

        log.info("已清除数据: {}", counts);
        return counts;
    }

    public List<String> getAllNetworkNames() {
        return deviceAccessConfigRepository.findAll().stream()
                .map(DeviceAccessConfig::getNetworkName)
                .filter(n -> n != null && !n.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 从 SQLite 同步表中获取可用网络列表（无需先同步设备配置）
     */
    public List<String> getAvailableNetworkNames() {
        if (!isTableExists("dmrelation") || !isTableExists("dmeo")) {
            return List.of();
        }
        // 从 dmeo(cid=1) 获取网络实例名称
        Map<String, String> netNameMap = new HashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList("SELECT oid, name FROM \"dmeo\" WHERE cid = 1")) {
            String oid = (String) row.get("oid");
            netNameMap.put(oid, row.get("name") != null ? (String) row.get("name") : oid);
        }
        // 从 dmrelation 获取归属关系中的网络 oid，取网络名称
        return sqliteJdbc.queryForList("SELECT DISTINCT reo FROM \"dmrelation\" WHERE type = 1 AND reo IS NOT NULL AND reo != ''")
                .stream()
                .map(row -> netNameMap.getOrDefault((String) row.get("reo"), ""))
                .filter(n -> !n.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    public boolean testSQLiteConnection() {
        try {
            long count = deviceAccessConfigRepository.count();
            log.info("SQLite数据库连接测试成功，共有{}条配置记录", count);
            return true;
        } catch (Exception e) {
            log.error("SQLite数据库连接测试失败", e);
            return false;
        }
    }

    private boolean isTableExists(String table) {
        Long count = sqliteJdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                Long.class, table);
        return count != null && count > 0;
    }
}
