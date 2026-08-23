package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.mysql.DmNe;
import com.optel.qxinspection.entity.mysql.DefDmNe;
import com.optel.qxinspection.entity.mysql.EmNeComm;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.mysql.DmNeRepository;
import com.optel.qxinspection.repository.mysql.DefDmNeRepository;
import com.optel.qxinspection.repository.mysql.EmNeCommRepository;
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
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;

    /**
     * 从MySQL老库同步设备信息到SQLite本地库
     */
    @Transactional
    public void syncDevicesFromMySQL() {
        log.info("开始从MySQL老库同步设备信息...");

        // 查询所有设备
        List<DmNe> allDevices = dmNeRepository.findAll();
        log.info("查询到{}台设备", allDevices.size());

        // 预加载设备类型名称映射
        Map<Integer, DefDmNe> typeMap = defDmNeRepository.findAll().stream()
                .collect(Collectors.toMap(DefDmNe::getNeType, d -> d));

        // 批量查询活跃通信配置
        List<String> allOids = allDevices.stream().map(DmNe::getOid).toList();
        Map<String, EmNeComm> commMap = emNeCommRepository.findActiveByOidIn(allOids).stream()
                .collect(Collectors.toMap(EmNeComm::getOid, c -> c));

        int syncCount = 0;
        for (DmNe dmNe : allDevices) {
            String oid = dmNe.getOid();
            EmNeComm comm = commMap.get(oid);
            DefDmNe typeInfo = typeMap.get(dmNe.getType());

            // 跳过没有活跃IP的设备
            if (comm == null || "0.0.0.0".equals(comm.getIpAddr())) {
                continue;
            }

            String deviceName = typeInfo != null ? typeInfo.getEName() : "Unknown";

            Optional<DeviceAccessConfig> existing = deviceAccessConfigRepository.findByNeId(oid);

            if (existing.isPresent()) {
                DeviceAccessConfig config = existing.get();
                config.setNeName(deviceName);
                config.setIpAddr(comm.getIpAddr());
                deviceAccessConfigRepository.save(config);
            } else {
                DeviceAccessConfig config = new DeviceAccessConfig();
                config.setNeId(oid);
                config.setNeName(deviceName);
                config.setIpAddr(comm.getIpAddr());
                config.setEnabled(true);
                config.setConnectionStatus(0);
                deviceAccessConfigRepository.save(config);
                syncCount++;
            }
        }

        log.info("同步完成，新增{}台设备配置", syncCount);
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
