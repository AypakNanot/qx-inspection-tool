package com.optel.qxinspection.controller;

import com.optel.qxinspection.service.DeviceStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final DeviceStatsService deviceStatsService;

    /**
     * 按设备类型统计（库存口径）
     */
    @GetMapping("/type")
    public List<Map<String, Object>> statsByType(@RequestParam(required = false) String network) {
        return deviceStatsService.statsByType(network);
    }

    /**
     * 按设备类型统计（在线口径）
     */
    @GetMapping("/type/online")
    public List<Map<String, Object>> statsByTypeOnline(@RequestParam(required = false) String network) {
        return deviceStatsService.statsByTypeOnline(network);
    }

    /**
     * 获取网络名称列表
     */
    @GetMapping("/networks")
    public List<String> getNetworkNames() {
        return deviceStatsService.getNetworkNames();
    }
}
