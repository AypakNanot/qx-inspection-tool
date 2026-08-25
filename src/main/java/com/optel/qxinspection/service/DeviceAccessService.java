package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.mysql.*;
import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.mysql.*;
import com.optel.qxinspection.repository.sqlite.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAccessService {

    private final DmNeRepository dmNeRepository;
    private final DefDmNeRepository defDmNeRepository;
    private final EmNeCommRepository emNeCommRepository;
    private final DmRelationRepository dmRelationRepository;
    private final DmNetRepository dmNetRepository;
    private final DefDmNetworkRepository defDmNetworkRepository;
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;
    private final ConnProfileRepository connProfileRepository;
    private final InspectionRoundRepository inspectionRoundRepository;
    private final OpticalPowerInspectionRepository opticalPowerInspectionRepository;
    private final ThresholdRuleRepository thresholdRuleRepository;
    private final DmeoRepository dmeoRepository;

    /**
     * 从MySQL老库同步设备信息到SQLite本地库
     * join: dmne + defdmne + emnecomm + dmrelation + dmnet + defdmnetwork
     */
    @Transactional
    public void syncDevicesFromMySQL() {
        log.info("开始从MySQL老库同步设备信息...");

        List<DmNe> allDevices = dmNeRepository.findAll();
        log.info("查询到{}台设备", allDevices.size());

        // 预加载设备类型名称映射
        Map<Integer, DefDmNe> typeMap = defDmNeRepository.findAll().stream()
                .collect(Collectors.toMap(DefDmNe::getNeType, d -> d));

        // 从 dmeo 表（cid=2）加载网元实例名称
        Map<String, String> neNameMap = dmeoRepository.findByCid(2).stream()
                .collect(Collectors.toMap(Dmeo::getOid, d -> d.getName() != null ? d.getName() : ""));

        // 批量查询活跃通信配置
        List<String> allOids = allDevices.stream().map(DmNe::getOid).toList();
        Map<String, EmNeComm> commMap = emNeCommRepository.findActiveByOidIn(allOids).stream()
                .collect(Collectors.toMap(EmNeComm::getOid, c -> c));

        // 批量查询网络归属（type=1 为归属关系）
        Map<String, String> netNameMap = buildNetworkNameMap(allOids);

        // 预加载已有配置到 Map，避免循环中逐条查询
        Map<String, DeviceAccessConfig> existingMap = deviceAccessConfigRepository.findAll().stream()
                .collect(Collectors.toMap(DeviceAccessConfig::getNeId, c -> c));

        List<DeviceAccessConfig> toSave = new java.util.ArrayList<>();
        int syncCount = 0;
        for (DmNe dmNe : allDevices) {
            String oid = dmNe.getOid();
            EmNeComm comm = commMap.get(oid);
            DefDmNe typeInfo = typeMap.get(dmNe.getType());

            // 跳过没有活跃IP的设备
            if (comm == null || "0.0.0.0".equals(comm.getIpAddr())) {
                continue;
            }

            String neName = neNameMap.getOrDefault(oid, typeInfo != null ? typeInfo.getCName() : "Unknown");
            String typeName = typeInfo != null ? typeInfo.getEName() : String.valueOf(dmNe.getType());
            String networkName = netNameMap.getOrDefault(oid, "");

            DeviceAccessConfig existing = existingMap.get(oid);
            if (existing != null) {
                existing.setNeName(neName);
                existing.setNeTypeName(typeName);
                existing.setNetworkName(networkName);
                existing.setIpAddr(comm.getIpAddr());
                toSave.add(existing);
            } else {
                DeviceAccessConfig config = new DeviceAccessConfig();
                config.setNeId(oid);
                config.setNeName(neName);
                config.setNeTypeName(typeName);
                config.setNetworkName(networkName);
                config.setIpAddr(comm.getIpAddr());
                config.setEnabled(true);
                config.setConnectionStatus(0);
                toSave.add(config);
                syncCount++;
            }
        }

        // 批量保存，一次 flush
        deviceAccessConfigRepository.saveAll(toSave);

        log.info("同步完成，新增{}台设备配置", syncCount);
    }

    /**
     * 构建网元oid → 网络名称映射
     * 使用 dmeo 表（cid=1）获取网络实例名称，而非网络类型名称
     */
    private Map<String, String> buildNetworkNameMap(List<String> oids) {
        // 查询所有网元的网络归属
        List<DmRelation> relations = dmRelationRepository.findAll().stream()
                .filter(r -> r.getType() == 1 && oids.contains(r.getOid()))
                .toList();

        // 获取涉及的网络oid
        List<String> netOids = relations.stream()
                .map(DmRelation::getReo)
                .filter(reo -> reo != null && !reo.isEmpty())
                .distinct()
                .toList();

        if (netOids.isEmpty()) {
            return Map.of();
        }

        // 从 dmeo 表（cid=1）获取网络实例名称
        Map<String, String> netNameMap = dmeoRepository.findByCid(1).stream()
                .collect(Collectors.toMap(Dmeo::getOid, d -> d.getName() != null ? d.getName() : d.getOid()));

        // 构建 网元oid → 网络名称
        Map<String, String> result = new java.util.HashMap<>();
        for (DmRelation rel : relations) {
            String netName = netNameMap.getOrDefault(rel.getReo(), rel.getReo());
            result.put(rel.getOid(), netName);
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
     * @param options 清除选项：
     *   inspectionRecords - 巡检记录
     *   inspectionRounds  - 巡检轮次
     *   deviceConfigs     - 设备配置
     *   connectionProfiles - 连接配置（值为 "all" 或网络名列表）
     *   thresholdRules    - 门限规则
     * @return 各表删除前的记录数
     */
    @Transactional(transactionManager = "sqliteTransactionManager")
    public Map<String, Long> clearSelectedData(Map<String, Object> options) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();

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

        // 连接配置：必须在设备配置删除之前处理（按网络筛选依赖设备配置数据）
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
                log.info("匹配到{}台设备, neIds={}", neIds.size(), neIds.size() > 5 ? neIds.size() + "个" : neIds);
                if (!neIds.isEmpty()) {
                    List<ConnProfile> allProfiles = connProfileRepository.findAll();
                    log.info("总连接配置数={}, NE配置数={}", allProfiles.size(),
                            allProfiles.stream().filter(p -> "NE".equals(p.getScope())).count());
                    List<ConnProfile> toDelete = allProfiles.stream()
                            .filter(p -> "NE".equals(p.getScope()) && neIds.contains(p.getNeOid()))
                            .toList();
                    long c = toDelete.size();
                    counts.put("连接配置(" + String.join(",", networkNames) + ")", c);
                    connProfileRepository.deleteAll(toDelete);
                    log.info("已删除{}条连接配置", c);
                } else {
                    log.info("未匹配到设备, 跳过连接配置清除");
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

    /**
     * 获取所有网络名称列表（从设备配置中提取）
     */
    public List<String> getAllNetworkNames() {
        return deviceAccessConfigRepository.findAll().stream()
                .map(DeviceAccessConfig::getNetworkName)
                .filter(n -> n != null && !n.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    public boolean testMySQLConnection() {
        try {
            long count = dmNeRepository.count();
            log.info("MySQL数据库连接测试成功，共有{}台设备", count);
            return true;
        } catch (Exception e) {
            log.error("MySQL数据库连接测试失败", e);
            return false;
        }
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
