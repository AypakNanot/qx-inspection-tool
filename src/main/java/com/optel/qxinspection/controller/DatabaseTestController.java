package com.optel.qxinspection.controller;

import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.service.DeviceAccessService;
import com.optel.qxinspection.service.MysqlConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库操作控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/database")
@RequiredArgsConstructor
public class DatabaseTestController {

    private final DeviceAccessService deviceAccessService;
    private final MysqlConnectionManager mysqlConnectionManager;

    /**
     * 测试MySQL数据库连接
     */
    @GetMapping("/test-mysql")
    public ResponseEntity<Map<String, Object>> testMySQL() {
        return ResponseEntity.ok(mysqlConnectionManager.testConnection());
    }

    /**
     * 测试SQLite数据库连接
     */
    @GetMapping("/test-sqlite")
    public ResponseEntity<Map<String, Object>> testSQLite() {
        Map<String, Object> result = new HashMap<>();
        boolean success = deviceAccessService.testSQLiteConnection();
        result.put("database", "SQLite");
        result.put("status", success ? "SUCCESS" : "FAILED");
        result.put("message", success ? "SQLite数据库连接成功" : "SQLite数据库连接失败");
        return ResponseEntity.ok(result);
    }

    /**
     * 选择性清除本地数据
     * 请求体示例：
     * {
     *   "inspectionRecords": true,
     *   "inspectionRounds": true,
     *   "deviceConfigs": true,
     *   "connectionProfiles": "all",       // 或 ["网络A","网络B"]
     *   "thresholdRules": true
     * }
     */
    @PostMapping("/clear-selected")
    public ResponseEntity<Map<String, Object>> clearSelectedData(@RequestBody Map<String, Object> options) {
        Map<String, Object> result = new java.util.HashMap<>();
        try {
            Map<String, Long> counts = deviceAccessService.clearSelectedData(options);
            result.put("status", "SUCCESS");
            result.put("message", "清除完成");
            result.put("deletedCounts", counts);
        } catch (Exception e) {
            log.error("清除数据失败", e);
            result.put("status", "FAILED");
            result.put("message", "清除数据失败，请重试");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 从SQLite同步设备信息
     * @param network 可选，按网络名称筛选，为空则同步全部
     */
    @PostMapping("/sync-devices")
    public ResponseEntity<Map<String, Object>> syncDevices(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        String network = body != null ? (String) body.get("network") : null;

        try {
            deviceAccessService.syncDevicesFromSQLite(network);
            result.put("status", "SUCCESS");
            result.put("message", "设备信息同步成功");
        } catch (Exception e) {
            log.error("设备信息同步失败", e);
            result.put("status", "FAILED");
            String msg = e.getMessage();
            result.put("message", msg != null && msg.length() < 100 ? msg : "设备信息同步失败，请检查数据维护中的同步状态");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有设备配置
     */
    @GetMapping("/devices")
    public ResponseEntity<List<DeviceAccessConfig>> getAllDevices() {
        List<DeviceAccessConfig> devices = deviceAccessService.getAllDeviceConfigs();
        return ResponseEntity.ok(devices);
    }

    /**
     * 获取已启用的设备配置
     */
    @GetMapping("/devices/enabled")
    public ResponseEntity<List<DeviceAccessConfig>> getEnabledDevices() {
        List<DeviceAccessConfig> devices = deviceAccessService.getEnabledDeviceConfigs();
        return ResponseEntity.ok(devices);
    }

    /**
     * 从同步表获取可用网络列表（无需先同步设备配置）
     */
    @GetMapping("/networks")
    public ResponseEntity<List<String>> getAvailableNetworks() {
        return ResponseEntity.ok(deviceAccessService.getAvailableNetworkNames());
    }
}
