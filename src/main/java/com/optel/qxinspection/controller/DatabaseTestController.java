package com.optel.qxinspection.controller;

import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.service.DeviceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库连接测试控制器
 * 
 * @author Rwj
 * @since 2026-08-20
 */
@Slf4j
@RestController
@RequestMapping("/api/database")
@RequiredArgsConstructor
public class DatabaseTestController {

    private final DeviceAccessService deviceAccessService;

    /**
     * 测试MySQL数据库连接
     */
    @GetMapping("/test-mysql")
    public ResponseEntity<Map<String, Object>> testMySQL() {
        Map<String, Object> result = new HashMap<>();
        
        boolean success = deviceAccessService.testMySQLConnection();
        result.put("database", "MySQL");
        result.put("status", success ? "SUCCESS" : "FAILED");
        result.put("message", success ? "MySQL数据库连接成功" : "MySQL数据库连接失败");
        
        return ResponseEntity.ok(result);
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
     * 测试所有数据库连接
     */
    @GetMapping("/test-all")
    public ResponseEntity<Map<String, Object>> testAll() {
        Map<String, Object> result = new HashMap<>();
        
        boolean mysqlSuccess = deviceAccessService.testMySQLConnection();
        boolean sqliteSuccess = deviceAccessService.testSQLiteConnection();
        
        Map<String, Object> mysql = new HashMap<>();
        mysql.put("status", mysqlSuccess ? "SUCCESS" : "FAILED");
        mysql.put("message", mysqlSuccess ? "连接成功" : "连接失败");
        
        Map<String, Object> sqlite = new HashMap<>();
        sqlite.put("status", sqliteSuccess ? "SUCCESS" : "FAILED");
        sqlite.put("message", sqliteSuccess ? "连接成功" : "连接失败");
        
        result.put("mysql", mysql);
        result.put("sqlite", sqlite);
        result.put("allSuccess", mysqlSuccess && sqliteSuccess);
        
        return ResponseEntity.ok(result);
    }

    /**
     * 从MySQL同步设备信息到SQLite
     */
    @PostMapping("/sync-devices")
    public ResponseEntity<Map<String, Object>> syncDevices() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            deviceAccessService.syncDevicesFromMySQL();
            result.put("status", "SUCCESS");
            result.put("message", "设备信息同步成功");
        } catch (Exception e) {
            log.error("设备信息同步失败", e);
            result.put("status", "FAILED");
            result.put("message", "设备信息同步失败: " + e.getMessage());
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
}
