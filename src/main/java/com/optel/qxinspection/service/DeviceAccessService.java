package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.mysql.*;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.mysql.*;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        // 批量查询活跃通信配置
        List<String> allOids = allDevices.stream().map(DmNe::getOid).toList();
        Map<String, EmNeComm> commMap = emNeCommRepository.findActiveByOidIn(allOids).stream()
                .collect(Collectors.toMap(EmNeComm::getOid, c -> c));

        // 批量查询网络归属（type=1 为归属关系）
        Map<String, String> netNameMap = buildNetworkNameMap(allOids);

        int syncCount = 0;
        for (DmNe dmNe : allDevices) {
            String oid = dmNe.getOid();
            EmNeComm comm = commMap.get(oid);
            DefDmNe typeInfo = typeMap.get(dmNe.getType());

            // 跳过没有活跃IP的设备
            if (comm == null || "0.0.0.0".equals(comm.getIpAddr())) {
                continue;
            }

            String deviceName = typeInfo != null ? typeInfo.getCName() : "Unknown";
            String typeName = typeInfo != null ? typeInfo.getEName() : String.valueOf(dmNe.getType());
            String networkName = netNameMap.getOrDefault(oid, "");

            Optional<DeviceAccessConfig> existing = deviceAccessConfigRepository.findByNeId(oid);

            if (existing.isPresent()) {
                DeviceAccessConfig config = existing.get();
                config.setNeName(deviceName);
                config.setNeTypeName(typeName);
                config.setNetworkName(networkName);
                config.setIpAddr(comm.getIpAddr());
                deviceAccessConfigRepository.save(config);
            } else {
                DeviceAccessConfig config = new DeviceAccessConfig();
                config.setNeId(oid);
                config.setNeName(deviceName);
                config.setNeTypeName(typeName);
                config.setNetworkName(networkName);
                config.setIpAddr(comm.getIpAddr());
                config.setEnabled(true);
                config.setConnectionStatus(0);
                deviceAccessConfigRepository.save(config);
                syncCount++;
            }
        }

        log.info("同步完成，新增{}台设备配置", syncCount);
    }

    /**
     * 构建网元oid → 网络名称映射
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

        // 加载网络信息
        Map<String, DmNet> netMap = dmNetRepository.findAllById(netOids).stream()
                .collect(Collectors.toMap(DmNet::getOid, n -> n));

        // 加载网络类型名称
        Map<Integer, DefDmNetwork> netTypeMap = defDmNetworkRepository.findAll().stream()
                .collect(Collectors.toMap(DefDmNetwork::getType, d -> d));

        // 构建 网元oid → 网络名称
        Map<String, String> result = new java.util.HashMap<>();
        for (DmRelation rel : relations) {
            DmNet net = netMap.get(rel.getReo());
            if (net != null) {
                DefDmNetwork netTypeInfo = netTypeMap.get(net.getType());
                String netName = netTypeInfo != null ? netTypeInfo.getCName() : "Network-" + rel.getReo();
                result.put(rel.getOid(), netName);
            }
        }
        return result;
    }

    public List<DeviceAccessConfig> getAllDeviceConfigs() {
        return deviceAccessConfigRepository.findAll();
    }

    public List<DeviceAccessConfig> getEnabledDeviceConfigs() {
        return deviceAccessConfigRepository.findByEnabledTrue();
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
