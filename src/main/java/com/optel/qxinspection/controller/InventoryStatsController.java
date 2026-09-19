package com.optel.qxinspection.controller;

import com.optel.qxinspection.service.InventoryStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryStatsController {

    private final InventoryStatsService inventoryStatsService;

    @GetMapping("/networks")
    public List<String> getNetworkNames() {
        return inventoryStatsService.getNetworkNames();
    }

    @GetMapping("/overview")
    public Map<String, Object> getOverview() {
        return inventoryStatsService.getOverview();
    }

    /**
     * 网元类型统计，支持 ?network= 筛选
     */
    @GetMapping("/ne-stats")
    public Map<String, Object> getNeStats(@RequestParam(required = false) String network) {
        return inventoryStatsService.getNeStats(network);
    }

    /**
     * 盘类型统计，支持 ?network= 筛选
     */
    @GetMapping("/slot-stats")
    public Map<String, Object> getSlotStats(@RequestParam(required = false) String network) {
        return inventoryStatsService.getSlotStats(network);
    }

    /**
     * 端口类型统计，支持 ?network= 筛选
     */
    @GetMapping("/port-stats")
    public Map<String, Object> getPortStats(@RequestParam(required = false) String network) {
        return inventoryStatsService.getPortStats(network);
    }
}
