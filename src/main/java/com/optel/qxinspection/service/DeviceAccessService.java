package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.sqlite.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeviceAccessService {

    private final JdbcTemplate sqliteJdbc;
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;
    private final ConnProfileRepository connProfileRepository;
    private final InspectionRoundRepository inspectionRoundRepository;
    private final LinkInspectionResultRepository linkResultRepository;
    private final AuditLogRepository auditLogRepository;

    public DeviceAccessService(@Qualifier("sqliteJdbc") JdbcTemplate sqliteJdbc,
                               DeviceAccessConfigRepository deviceAccessConfigRepository,
                               ConnProfileRepository connProfileRepository,
                               InspectionRoundRepository inspectionRoundRepository,
                               LinkInspectionResultRepository linkResultRepository,
                               AuditLogRepository auditLogRepository) {
        this.sqliteJdbc = sqliteJdbc;
        this.deviceAccessConfigRepository = deviceAccessConfigRepository;
        this.connProfileRepository = connProfileRepository;
        this.inspectionRoundRepository = inspectionRoundRepository;
        this.linkResultRepository = linkResultRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 从 SQLite dmeo 表（cid=2）生成设备配置。
     * dmeo 表已冗余所有字段（neName, neTypeName, networkName, ipAddr），无需 JOIN。
     */
    @Transactional(transactionManager = "sqliteTransactionManager")
    public void syncDevicesFromSQLite() {
        log.info("开始从 dmeo 表生成设备配置");

        // 检查 dmeo 表是否有数据
        Long dmeoCount = sqliteJdbc.queryForObject("SELECT COUNT(*) FROM dmeo WHERE cid = 2", Long.class);
        if (dmeoCount == null || dmeoCount == 0) {
            throw new IllegalStateException("设备数据尚未同步，请先在「维护 → 数据维护」页面执行同步");
        }

        // 直接从 dmeo cid=2 获取所有 NE 数据（含冗余字段）
        List<Map<String, Object>> allNe = sqliteJdbc.queryForList(
                "SELECT oid, name, neTypeName, networkName, ipAddr FROM dmeo WHERE cid = 2");

        // 预加载已有配置
        Map<String, DeviceAccessConfig> existingMap = deviceAccessConfigRepository.findAll().stream()
                .collect(Collectors.toMap(DeviceAccessConfig::getNeId, c -> c));

        List<DeviceAccessConfig> toSave = new ArrayList<>();
        int syncCount = 0;

        for (Map<String, Object> ne : allNe) {
            String oid = (String) ne.get("oid");
            String ipAddr = ne.get("ipAddr") != null ? (String) ne.get("ipAddr") : "";

            // 跳过没有活跃 IP 的设备
            if (ipAddr.isEmpty() || "0.0.0.0".equals(ipAddr)) continue;

            String neName = ne.get("name") != null ? (String) ne.get("name") : oid;
            String neTypeName = ne.get("neTypeName") != null ? (String) ne.get("neTypeName") : "未知";
            String networkName = ne.get("networkName") != null ? (String) ne.get("networkName") : "";

            DeviceAccessConfig existing = existingMap.get(oid);
            if (existing != null) {
                existing.setNeName(neName);
                existing.setNeTypeName(neTypeName);
                existing.setNetworkName(networkName);
                existing.setIpAddr(ipAddr);
                toSave.add(existing);
            } else {
                DeviceAccessConfig config = new DeviceAccessConfig();
                config.setNeId(oid);
                config.setNeName(neName);
                config.setNeTypeName(neTypeName);
                config.setNetworkName(networkName);
                config.setIpAddr(ipAddr);
                config.setEnabled(true);
                config.setConnectionStatus(0);
                toSave.add(config);
                syncCount++;
            }
        }

        deviceAccessConfigRepository.saveAll(toSave);
        log.info("设备配置同步完成: 更新/新增 {} 台, 新增 {} 台", toSave.size(), syncCount);
    }

    public List<DeviceAccessConfig> getAllDeviceConfigs() {
        return deviceAccessConfigRepository.findAll();
    }

    public List<DeviceAccessConfig> getEnabledDeviceConfigs() {
        return deviceAccessConfigRepository.findByEnabledTrue();
    }

    /**
     * 选择性清除本地 SQLite 数据
     */
    @Transactional(transactionManager = "sqliteTransactionManager")
    public Map<String, Long> clearSelectedData(Map<String, Object> options) {
        Map<String, Long> counts = new LinkedHashMap<>();

        boolean clearRecords = Boolean.TRUE.equals(options.get("inspectionRecords"));
        boolean clearRounds = Boolean.TRUE.equals(options.get("inspectionRounds"));
        boolean clearDevices = Boolean.TRUE.equals(options.get("deviceConfigs"));
        boolean clearAuditLogs = Boolean.TRUE.equals(options.get("auditLogs"));
        Object connOpt = options.get("connectionProfiles");

        if (clearRecords) {
            long c = linkResultRepository.count();
            counts.put("巡检记录", c);
            linkResultRepository.deleteAllInBatch();
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
        if (clearAuditLogs) {
            long c = auditLogRepository.count();
            counts.put("操作日志", c);
            auditLogRepository.deleteAllInBatch();
        }

        log.info("已清除数据: {}", counts);
        return counts;
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
}