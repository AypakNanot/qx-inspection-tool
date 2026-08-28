package com.optel.qxinspection.controller;

import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import com.optel.qxinspection.repository.sqlite.ThresholdRuleRepository;
import com.optel.qxinspection.service.ClockInspectionService;
import com.optel.qxinspection.service.InspectionScheduler;
import com.optel.qxinspection.service.InspectionService;
import com.optel.qxinspection.service.ThresholdService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/inspection")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;
    private final InspectionScheduler inspectionScheduler;
    private final ThresholdService thresholdService;
    private final ThresholdRuleRepository thresholdRuleRepository;
    private final ClockInspectionService clockInspectionService;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 触发全网巡检
     */
    @PostMapping("/start")
    public Map<String, Object> startInspection(@RequestParam(required = false) String network,
                                               @RequestParam(required = false) String neId) {
        InspectionRound round;
        if (neId != null && !neId.isEmpty()) {
            round = inspectionService.triggerInspectionByNe(neId);
        } else if (network != null && !network.isEmpty()) {
            round = inspectionService.triggerInspectionByNetwork(network);
        } else {
            round = inspectionService.triggerInspectionAll();
        }
        return Map.of(
                "roundId", round.getId(),
                "status", round.getStatus(),
                "totalDevices", round.getTotalCount()
        );
    }

    /**
     * 查询巡检进度
     */
    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        return inspectionService.getProgress();
    }

    /**
     * 查询巡检摘要
     */
    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        return inspectionService.getSummary();
    }

    /**
     * 查询巡检轮次列表
     */
    @GetMapping("/rounds")
    public List<InspectionRound> listRounds() {
        return inspectionService.listRounds();
    }

    /**
     * 查询巡检结果（最新轮次或指定轮次）
     */
    @GetMapping("/results")
    public List<OpticalPowerInspection> getResults(
            @RequestParam(required = false) Long roundId,
            @RequestParam(required = false) String neId,
            @RequestParam(required = false) String network) {
        if (roundId != null) {
            List<OpticalPowerInspection> data = inspectionService.getResultsByRound(roundId, network);
            if (neId != null && !neId.isEmpty()) {
                return data.stream().filter(r -> neId.equals(r.getNeId())).toList();
            }
            return data;
        }
        if (neId != null && !neId.isEmpty()) {
            return inspectionService.getResultsByNe(neId);
        }
        return inspectionService.getLatestResults(network);
    }

    /**
     * 导出巡检结果为 Excel
     */
    @GetMapping("/export")
    public void exportExcel(@RequestParam(required = false) Long roundId,
                            @RequestParam(required = false) String network,
                            HttpServletResponse response) throws IOException {
        List<OpticalPowerInspection> data;
        if (roundId != null) {
            data = inspectionService.getResultsByRound(roundId, network);
        } else {
            data = inspectionService.getLatestResults(network);
        }

        // 动态文件名
        String scope = (network != null && !network.isEmpty()) ? network : "全网";
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String filename = "光功率_" + scope + "_" + timestamp + ".xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String encodedName = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"optical_power.xlsx\"; filename*=UTF-8''" + encodedName);

        try (Workbook workbook = new XSSFWorkbook();
             OutputStream out = response.getOutputStream()) {

            Sheet sheet = workbook.createSheet("光功率巡检结果");

            // 标题行
            Row header = sheet.createRow(0);
            String[] columns = {"网元名称", "网元ID", "所属网络", "设备类型", "槽位", "端口", "端口名称", "激光器状态",
                    "光模块速率", "距离档", "模块型号", "生产厂商",
                    "发送功率(dBm)", "接收功率(dBm)", "发送状态", "接收状态",
                    "发送低门限", "发送高门限", "接收低门限", "接收高门限", "巡检时间", "备注"};

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            for (int i = 0; i < columns.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            // 数据行
            CellStyle normalStyle = workbook.createCellStyle();
            normalStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle warnStyle = workbook.createCellStyle();
            warnStyle.setAlignment(HorizontalAlignment.CENTER);
            Font warnFont = workbook.createFont();
            warnFont.setColor(IndexedColors.RED.getIndex());
            warnStyle.setFont(warnFont);

            int rowIdx = 1;
            for (OpticalPowerInspection r : data) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(r.getNeName() != null ? r.getNeName() : "");
                row.createCell(1).setCellValue(r.getNeId() != null ? r.getNeId() : "");
                row.createCell(2).setCellValue(r.getNetworkName() != null ? r.getNetworkName() : "");
                row.createCell(3).setCellValue(r.getNeTypeName() != null ? r.getNeTypeName() : "");
                row.createCell(4).setCellValue(r.getSlotNo() != null ? r.getSlotNo() : 0);
                row.createCell(5).setCellValue(r.getPortNo() != null ? r.getPortNo() : 0);
                row.createCell(6).setCellValue(r.getPortName() != null ? r.getPortName() : "");
                row.createCell(7).setCellValue(formatLaserState(r.getLaserState()));
                row.createCell(8).setCellValue(r.getLaserType() != null ? r.getLaserType() : "--");
                row.createCell(9).setCellValue(r.getLaserDistance() != null ? r.getLaserDistance() : "--");
                row.createCell(10).setCellValue(r.getModuleTypeKey() != null ? r.getModuleTypeKey() : "--");
                row.createCell(11).setCellValue(r.getVendorName() != null ? r.getVendorName() : "--");

                // 功率值（保留1位小数）
                Cell txCell = row.createCell(12);
                Cell rxCell = row.createCell(13);
                if (Boolean.TRUE.equals(r.getSupported()) && r.getTxPower() != null) {
                    txCell.setCellValue(String.format("%.1f", r.getTxPower()));
                    rxCell.setCellValue(r.getRxPower() != null ? String.format("%.1f", r.getRxPower()) : "--");
                } else {
                    txCell.setCellValue("--");
                    rxCell.setCellValue("--");
                }

                // 状态标色
                Cell txStatusCell = row.createCell(14);
                String txStatus = formatStatus(r.getTxPowerStatus());
                txStatusCell.setCellValue(txStatus);
                txStatusCell.setCellStyle(r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0 ? warnStyle : normalStyle);

                Cell rxStatusCell = row.createCell(15);
                String rxStatus = formatStatus(r.getRxPowerStatus());
                rxStatusCell.setCellValue(rxStatus);
                rxStatusCell.setCellStyle(r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0 ? warnStyle : normalStyle);

                row.createCell(16).setCellValue(r.getTxLowThreshold() != null ? r.getTxLowThreshold() : 0);
                row.createCell(17).setCellValue(r.getTxHighThreshold() != null ? r.getTxHighThreshold() : 0);
                row.createCell(18).setCellValue(r.getLowThreshold() != null ? r.getLowThreshold() : 0);
                row.createCell(19).setCellValue(r.getHighThreshold() != null ? r.getHighThreshold() : 0);
                row.createCell(20).setCellValue(r.getInspectionTime() != null ? r.getInspectionTime().format(DT_FMT) : "");
                row.createCell(21).setCellValue(r.getFailReason() != null ? r.getFailReason() : "");
            }

            // 自动列宽
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            out.flush();
        }
    }

    private String formatStatus(Integer status) {
        if (status == null) return "--";
        return switch (status) {
            case 0 -> "正常";
            case 1 -> "越下限";
            case 2 -> "越上限";
            default -> "未知";
        };
    }

    private String formatLaserState(Integer state) {
        if (state == null) return "--";
        return switch (state) {
            case 1 -> "开";
            case 2 -> "关";
            default -> "未知";
        };
    }

    // ========== 定时巡检 ==========

    /**
     * 查询定时巡检状态
     */
    @GetMapping("/schedule")
    public Map<String, Object> getScheduleStatus() {
        return inspectionScheduler.getStatus();
    }

    /**
     * 启用/禁用定时巡检
     */
    @PostMapping("/schedule/toggle")
    public Map<String, Object> toggleSchedule(@RequestParam boolean enabled) {
        inspectionScheduler.setEnabled(enabled);
        return inspectionScheduler.getStatus();
    }

    /**
     * 保存定时巡检配置
     */
    @PostMapping("/schedule/config")
    public Map<String, Object> saveScheduleConfig(@RequestBody Map<String, Object> body) {
        boolean enabled = (Boolean) body.getOrDefault("enabled", false);
        String scope = (String) body.getOrDefault("scope", "ALL");
        String network = (String) body.getOrDefault("network", "");
        String cron = (String) body.getOrDefault("cronExpression", "0 0 2 * * ?");
        inspectionScheduler.updateConfig(enabled, scope, network, cron);
        return inspectionScheduler.getStatus();
    }

    /**
     * 获取采集参数
     */
    @GetMapping("/collect-params")
    public Map<String, Object> getCollectParams() {
        Map<String, Object> params = inspectionService.getCollectParams();
        log.debug("getCollectParams: {}", params);
        return params;
    }

    /**
     * 保存采集参数
     */
    @PostMapping("/collect-params")
    public Map<String, Object> saveCollectParams(@RequestBody Map<String, Object> body) {
        int concurrency = ((Number) body.getOrDefault("concurrency", 10)).intValue();
        int maxRounds = ((Number) body.getOrDefault("maxRounds", 10)).intValue();
        boolean autoConnect = Boolean.TRUE.equals(body.get("autoConnect"));
        boolean autoDisconnect = Boolean.TRUE.equals(body.get("autoDisconnect"));
        inspectionService.updateCollectParams(concurrency, maxRounds, autoConnect, autoDisconnect);
        return inspectionService.getCollectParams();
    }

    // ========== 趋势与异常 ==========

    /**
     * 单端口历史趋势
     */
    @GetMapping("/trend/port")
    public List<OpticalPowerInspection> getPortTrend(@RequestParam String neId,
                                                      @RequestParam int slotNo,
                                                      @RequestParam int portNo) {
        return inspectionService.getPortTrend(neId, slotNo, portNo);
    }

    /**
     * 网元历史趋势
     */
    @GetMapping("/trend/ne")
    public List<OpticalPowerInspection> getNeTrend(@RequestParam String neId) {
        return inspectionService.getNeTrend(neId);
    }

    /**
     * 越限异常汇总（按网元分组）
     */
    @GetMapping("/anomaly/summary")
    public List<Map<String, Object>> getAnomalySummary(
            @RequestParam(required = false) Long roundId) {
        return inspectionService.getAnomalySummary(roundId);
    }

    /**
     * 越限详细记录
     */
    @GetMapping("/anomaly/details")
    public List<OpticalPowerInspection> getAnomalyDetails(
            @RequestParam(required = false) Long roundId) {
        return inspectionService.getOverThresholdRecords(roundId);
    }

    // ========== 门限管理 ==========

    /**
     * 查询所有门限规则
     */
    @GetMapping("/thresholds")
    public List<ThresholdRule> listThresholds() {
        return thresholdRuleRepository.findAll();
    }

    /**
     * 获取门限快照（用于导出）
     */
    @GetMapping("/thresholds/snapshot")
    public Map<String, Object> getThresholdSnapshot() {
        return thresholdService.getThresholdSnapshot();
    }

    /**
     * 创建或更新门限规则
     */
    @PostMapping("/thresholds")
    public ThresholdRule saveThreshold(@RequestBody ThresholdRule rule) {
        // 查找已有的同级别同key规则
        ThresholdRule existing = thresholdRuleRepository
                .findByLevelTypeAndMatchKey(rule.getLevelType(), rule.getMatchKey())
                .orElse(null);
        if (existing != null) {
            existing.setTxLow(rule.getTxLow());
            existing.setTxHigh(rule.getTxHigh());
            existing.setRxLow(rule.getRxLow());
            existing.setRxHigh(rule.getRxHigh());
            existing.setDescription(rule.getDescription());
            return thresholdRuleRepository.save(existing);
        }
        return thresholdRuleRepository.save(rule);
    }

    /**
     * 删除门限规则
     */
    @DeleteMapping("/thresholds/{id}")
    public Map<String, Object> deleteThreshold(@PathVariable Long id) {
        thresholdRuleRepository.deleteById(id);
        return Map.of("success", true);
    }

    // ========== 时钟拓扑 ==========

    /**
     * 获取全网时钟拓扑
     */
    @GetMapping("/clock/topology")
    public List<ClockInspectionService.ClockNode> getClockTopology() {
        return clockInspectionService.getTopology();
    }

    /**
     * 刷新时钟拓扑数据
     */
    @PostMapping("/clock/refresh")
    public List<ClockInspectionService.ClockNode> refreshClockTopology() {
        return clockInspectionService.refreshTopology();
    }
}
