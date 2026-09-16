package com.optel.qxinspection.controller;

import com.optel.qxinspection.service.BandwidthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 端口带宽利用率查询接口。
 * <p>
 * 所有数据均从 SQLite 本地库读取，不访问远程 MySQL。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/bandwidth")
@RequiredArgsConstructor
public class BandwidthController {

    private final BandwidthService bandwidthService;

    /**
     * 查询全网端口带宽概览。
     * <p>返回所有符合带宽过滤条件的端口，合并光功率数据</p>
     */
    @GetMapping("/all")
    public List<Map<String, Object>> getAll() {
        return bandwidthService.queryAll();
    }

    /**
     * 查询指定网元的端口带宽。
     *
     * @param neId 网元ID
     */
    @GetMapping("/ne/{neId}")
    public List<Map<String, Object>> getByNe(@PathVariable String neId) {
        return bandwidthService.queryByNe(neId);
    }

    /**
     * 查询带宽汇总统计。
     *
     * @param neId 网元ID（可选，不传则统计全网）
     */
    @GetMapping("/summary")
    public Map<String, Object> getSummary(@RequestParam(required = false) String neId) {
        return bandwidthService.getSummary(neId);
    }
}
