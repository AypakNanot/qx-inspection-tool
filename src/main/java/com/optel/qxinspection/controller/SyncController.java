package com.optel.qxinspection.controller;

import com.optel.qxinspection.service.DynamicSyncService;
import com.optel.qxinspection.service.MysqlConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
@Slf4j
public class SyncController {

    private final DynamicSyncService dynamicSyncService;
    private final MysqlConnectionManager mysqlConnectionManager;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus() {
        return ResponseEntity.ok(dynamicSyncService.getSyncStatus());
    }

    @GetMapping("/tables")
    public ResponseEntity<List<String>> getAllTables() {
        return ResponseEntity.ok(dynamicSyncService.getAllTables());
    }

    @PostMapping("/all")
    public ResponseEntity<Map<String, Object>> syncAll() {
        return ResponseEntity.ok(dynamicSyncService.syncAll());
    }

    @PostMapping("/essential")
    public ResponseEntity<Map<String, Object>> syncEssential() {
        return ResponseEntity.ok(dynamicSyncService.syncEssential());
    }

    @PostMapping("/tables")
    public ResponseEntity<Map<String, Object>> syncTables(@RequestBody List<String> tables) {
        return ResponseEntity.ok(dynamicSyncService.syncTables(tables));
    }

    @GetMapping("/query")
    public ResponseEntity<List<Map<String, Object>>> query(
            @RequestParam String table,
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String orderBy,
            @RequestParam(required = false) Integer limit) {
        List<String> fieldList = fields != null ?
                List.of(fields.split(",")) : null;
        return ResponseEntity.ok(dynamicSyncService.query(
                table, fieldList, null, orderBy, limit));
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearSyncData() {
        Map<String, Long> counts = dynamicSyncService.clearSyncData();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("deletedCounts", counts);
        return ResponseEntity.ok(result);
    }

    // ========== MySQL 配置管理 ==========

    @GetMapping("/mysql-config")
    public ResponseEntity<Map<String, Object>> getMysqlConfig() {
        return ResponseEntity.ok(mysqlConnectionManager.getConfig());
    }

    @PutMapping("/mysql-config")
    public ResponseEntity<Map<String, Object>> saveMysqlConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            String host = (String) body.getOrDefault("host", "");
            String username = (String) body.getOrDefault("username", "");
            String password = (String) body.getOrDefault("password", "");

            if (host.isEmpty()) {
                result.put("status", "FAILED");
                result.put("message", "主机地址不能为空");
                return ResponseEntity.ok(result);
            }

            mysqlConnectionManager.saveConfig(host, username, password);
            result.put("status", "SUCCESS");
            result.put("message", "MySQL 配置已保存");
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("message", "保存失败，请重试");
            log.error("保存MySQL配置失败", e);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/mysql-test")
    public ResponseEntity<Map<String, Object>> testMysqlConnection() {
        return ResponseEntity.ok(mysqlConnectionManager.testConnection());
    }
}
