package com.optel.qxinspection.controller;

import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.service.QxConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connection")
@RequiredArgsConstructor
public class ConnectionController {

    private final QxConnectionService qxConnectionService;

    // ===== 批量操作 =====

    @PostMapping("/connect-all")
    public ResponseEntity<Map<String, Object>> connectAll(
            @RequestParam(required = false) String network) {
        return ResponseEntity.ok(qxConnectionService.connectAll(network));
    }

    @PostMapping("/disconnect-all")
    public ResponseEntity<Map<String, Object>> disconnectAll(
            @RequestParam(required = false) String network) {
        return ResponseEntity.ok(qxConnectionService.disconnectAll(network));
    }

    // ===== 单设备操作 =====

    @PostMapping("/connect/{neOid}")
    public ResponseEntity<Map<String, Object>> connectSingle(@PathVariable String neOid) {
        boolean ok = qxConnectionService.connectSingle(neOid);
        return ResponseEntity.ok(Map.of("neOid", neOid, "success", ok));
    }

    @PostMapping("/disconnect/{neOid}")
    public ResponseEntity<Map<String, Object>> disconnectSingle(@PathVariable String neOid) {
        boolean ok = qxConnectionService.disconnectSingle(neOid);
        return ResponseEntity.ok(Map.of("neOid", neOid, "success", ok));
    }

    // ===== 状态查询 =====

    @GetMapping("/status")
    public ResponseEntity<List<Map<String, Object>>> getStatus() {
        return ResponseEntity.ok(qxConnectionService.getConnectionStatus());
    }

    @GetMapping("/status/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        return ResponseEntity.ok(qxConnectionService.getConnectionSummary());
    }

    // ===== 全局配置 =====

    @GetMapping("/config/global")
    public ResponseEntity<?> getGlobalConfig() {
        return qxConnectionService.getGlobalConfig()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/config/global")
    public ResponseEntity<ConnProfile> saveGlobalConfig(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        int port = body.containsKey("port") ? (int) body.get("port") : 9900;
        return ResponseEntity.ok(qxConnectionService.saveGlobalConfig(username, password, port));
    }

    // ===== 单设备配置 =====

    @GetMapping("/config/{neOid}")
    public ResponseEntity<?> getDeviceConfig(@PathVariable String neOid) {
        return qxConnectionService.getDeviceConfig(neOid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/config/{neOid}")
    public ResponseEntity<ConnProfile> saveDeviceConfig(@PathVariable String neOid,
                                                         @RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        Integer port = body.containsKey("port") ? (Integer) body.get("port") : null;
        return ResponseEntity.ok(qxConnectionService.saveDeviceConfig(neOid, username, password, port));
    }

    @DeleteMapping("/config/{neOid}")
    public ResponseEntity<Map<String, Object>> deleteDeviceConfig(@PathVariable String neOid) {
        qxConnectionService.deleteDeviceConfig(neOid);
        return ResponseEntity.ok(Map.of("deleted", neOid));
    }
}
