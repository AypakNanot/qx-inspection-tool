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
     * 盘类型列表（cid=4）
     */
    @GetMapping("/slot/types")
    public List<Integer> getSlotTypes() {
        return inventoryStatsService.getObjectTypes(4);
    }

    /**
     * 端口类型列表（cid=5）
     */
    @GetMapping("/port/types")
    public List<Integer> getPortTypes() {
        return inventoryStatsService.getObjectTypes(5);
    }

    /**
     * 网元统计，支持 ?network= 筛选
     */
    @GetMapping("/ne")
    public Map<String, Object> getNeStats(@RequestParam(required = false) String network) {
        return inventoryStatsService.getNeStats(network);
    }

    /**
     * 盘统计，支持 ?network= 和 ?objectType= 筛选
     */
    @GetMapping("/slot")
    public Map<String, Object> getSlotStats(@RequestParam(required = false) String network,
                                            @RequestParam(required = false) Integer objectType) {
        return inventoryStatsService.getSlotStats(network, objectType);
    }

    /**
     * 端口统计，支持 ?network= 和 ?objectType= 筛选
     */
    @GetMapping("/port")
    public Map<String, Object> getPortStats(@RequestParam(required = false) String network,
                                            @RequestParam(required = false) Integer objectType) {
        return inventoryStatsService.getPortStats(network, objectType);
    }
}
