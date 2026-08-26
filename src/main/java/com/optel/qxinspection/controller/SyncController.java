package com.optel.qxinspection.controller;

import com.optel.qxinspection.service.DynamicSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final DynamicSyncService dynamicSyncService;

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
}
