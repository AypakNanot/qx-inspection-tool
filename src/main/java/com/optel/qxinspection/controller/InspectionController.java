package com.optel.qxinspection.controller;

import com.optel.qxinspection.entity.sqlite.AuditLog;
import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.entity.sqlite.LinkInspectionResult;
import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import com.optel.qxinspection.service.AuditService;
import com.optel.qxinspection.service.InspectionScheduler;
import com.optel.qxinspection.service.InspectionService;
import com.optel.qxinspection.service.ThresholdService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/inspection")
@RequiredArgsConstructor
public class InspectionController {

    /** 导出：空白占位 */
    private static final String EMPTY_CELL = "--";

    /** 导出：主表列数（序号 + 链路名称 + A端12列 + Z端12列 + 链路带宽2列） */
    private static final int EXPORT_COLUMNS = 28;

    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 审计日志中的目标名 */
    private static final String AUDIT_TARGET_SQLITE = "SQLite数据库";

    /** 恢复前自动留存的快照文件名 */
    private static final String PRE_RESTORE_BACKUP = "qx_inspection_backup_before_restore.db";

    /** 恢复时校验的必需表：`sqlite_master` 里至少要有这些 */
    private static final List<String> REQUIRED_TABLES =
            List.of("link_inspection_result", "inspection_round", "device_access_config");

    private static final String JDBC_SQLITE_PREFIX = "jdbc:sqlite:";

    private final InspectionService inspectionService;
    private final InspectionScheduler inspectionScheduler;
    private final AuditService auditService;
    private final ThresholdService thresholdService;
    private final org.springframework.jdbc.core.JdbcTemplate sqliteJdbc;

    @Value("${app.admin-token:}")
    private String adminToken;

    @Value("${spring.datasource.sqlite.url}")
    private String sqliteUrl;

    /**
     * 触发巡检
     */
    @PostMapping("/start")
    public Map<String, Object> startInspection(@RequestParam(required = false) String network,
                                               @RequestParam(required = false) String neId) {
        InspectionRound round;
        String scope;
        if (neId != null && !neId.isEmpty()) {
            round = inspectionService.triggerInspectionByNe(neId);
            scope = "neId=" + neId;
        } else if (network != null && !network.isEmpty()) {
            round = inspectionService.triggerInspectionByNetwork(network);
            scope = "network=" + network;
        } else {
            round = inspectionService.triggerInspectionAll();
            scope = "全网";
        }
        auditService.record("INSPECTION", scope, "SUCCESS", "轮次#" + round.getId());
        return Map.of(
                "roundId", round.getId(),
                "status", round.getStatus(),
                "totalLinks", round.getTotalCount()
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
     * 查询链路巡检结果（A端+Z端同一行）
     */
    @GetMapping("/link-results")
    public List<LinkInspectionResult> getLinkResults(@RequestParam(required = false) Long roundId,
                                                     @RequestParam(required = false) String network) {
        return inspectionService.getLinkResults(roundId, network);
    }

    /**
     * 导出链路巡检结果为 Excel（26 列 / 3 行合并表头 + 说明 Sheet）
     */
    @GetMapping("/export-link")
    public void exportLinkExcel(@RequestParam(required = false) Long roundId,
                                @RequestParam(required = false) String network,
                                HttpServletResponse response) throws IOException {
        List<LinkInspectionResult> data = inspectionService.getLinkResults(roundId, network);

        String scope = (network != null && !network.isEmpty()) ? network : "全网";
        String timestamp = LocalDateTime.now().format(FILE_TIME_FMT);
        String filename = "链路巡检_" + scope + "_" + timestamp + ".xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"link_inspection.xlsx\"; filename*=UTF-8''" + encodedName);

        try (Workbook workbook = new XSSFWorkbook();
             OutputStream out = response.getOutputStream()) {
            writeResultSheet(workbook, data);
            writeThresholdSheet(workbook);
            workbook.write(out);
            out.flush();
        }
    }

    // ========== 导出实现 ==========

    private void writeResultSheet(Workbook workbook, List<LinkInspectionResult> data) {
        Sheet sheet = workbook.createSheet("巡检结果");

        CellStyle headStyle = headerStyle(workbook);
        CellStyle bodyStyle = bodyStyle(workbook);
        CellStyle warnStyle = warnStyle(workbook);

        // 第 1 行：序号 | 链路名称 | A端 | Z端 | 链路带宽
        Row row0 = styledRow(sheet, 0, headStyle);
        row0.getCell(0).setCellValue("序号");
        row0.getCell(1).setCellValue("链路名称");
        row0.getCell(2).setCellValue("A端");
        row0.getCell(14).setCellValue("Z端");
        row0.getCell(26).setCellValue("链路带宽");
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 2, 13));
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 14, 25));
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 26, 27));
        // 序号 / 链路名称 贯穿 3 行表头
        mergeVertically(sheet, 0, 2);

        // 第 2 行：网元(3) | 光模块(5) | 误码(1) | 带宽(3)
        Row row1 = styledRow(sheet, 1, headStyle);
        row1.getCell(2).setCellValue("网元");
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 2, 4));
        row1.getCell(5).setCellValue("光模块");
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 5, 9));
        row1.getCell(10).setCellValue("误码");
        row1.getCell(11).setCellValue("带宽");
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 11, 13));
        row1.getCell(14).setCellValue("网元");
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 14, 16));
        row1.getCell(17).setCellValue("光模块");
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 17, 21));
        row1.getCell(22).setCellValue("误码");
        row1.getCell(23).setCellValue("带宽");
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 23, 25));

        // 第 3 行：明细列名
        Row row2 = styledRow(sheet, 2, headStyle);
        String[] labels = {
                null, null,
                "名称", "类型", "端口", "类型", "TX\n(dBm)", "RX\n(dBm)", "TX\n状态", "RX\n状态", "RS\n(错误秒)",
                "总\n(Mbps)", "已用\n(Mbps)", "利用率",
                "名称", "类型", "端口", "类型", "TX\n(dBm)", "RX\n(dBm)", "TX\n状态", "RX\n状态", "RS\n(错误秒)",
                "总\n(Mbps)", "已用\n(Mbps)", "利用率",
                "总\n(Mbps)", "已用\n(Mbps)"
        };
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != null) {
                row2.getCell(i).setCellValue(labels[i]);
            }
        }

        int rowIdx = 3;
        for (LinkInspectionResult r : data) {
            Row row = sheet.createRow(rowIdx++);
            for (int i = 0; i < EXPORT_COLUMNS; i++) {
                row.createCell(i).setCellStyle(bodyStyle);
            }
            row.getCell(0).setCellValue(r.getSeqNo() != null ? r.getSeqNo() : 0);
            row.getCell(1).setCellValue(nullToEmpty(r.getLinkName()));
            writeSide(row, 2, r.getANeName(), r.getANeTypeName(), r.getAPortName(), r.getAModuleType(),
                    r.getATxPower(), r.getARxPower(), r.getATxStatus(), r.getARxStatus(),
                    r.getARsErrorSec(), r.getATotalBandwidth(), r.getAUsedBandwidth(),
                    r.getABandwidthUsage(), warnStyle);
            writeSide(row, 14, r.getZNeName(), r.getZNeTypeName(), r.getZPortName(), r.getZModuleType(),
                    r.getZTxPower(), r.getZRxPower(), r.getZTxStatus(), r.getZRxStatus(),
                    r.getZRsErrorSec(), r.getZTotalBandwidth(), r.getZUsedBandwidth(),
                    r.getZBandwidthUsage(), warnStyle);
            // 链路级别带宽
            setNumberOrDash(row.getCell(26), r.getLinkTotalBandwidth());
            setNumberOrDash(row.getCell(27), r.getLinkUsedBandwidth());
        }

        sheet.createFreezePane(0, 3);
        autoSizeColumns(sheet, EXPORT_COLUMNS);
    }

    /**
     * 写入一端（A端/Z端）的 12 列数据。
     */
    private void writeSide(Row row, int start, String neName, String neTypeName, String portName,
                           String moduleType, Double txPower, Double rxPower, String txStatus,
                           String rxStatus, Integer rsErrorSec, Double totalBandwidth,
                           Double usedBandwidth, Double bandwidthUsage, CellStyle warnStyle) {
        row.getCell(start).setCellValue(nullToEmpty(neName));
        setNeType(row.getCell(start + 1), neTypeName);
        row.getCell(start + 2).setCellValue(nullToEmpty(portName));
        row.getCell(start + 3).setCellValue(nullToEmpty(moduleType));
        setNumberOrDash(row.getCell(start + 4), txPower);
        setNumberOrDash(row.getCell(start + 5), rxPower);
        setStatus(row.getCell(start + 6), txStatus, warnStyle);
        setStatus(row.getCell(start + 7), rxStatus, warnStyle);
        setIntegerOrDash(row.getCell(start + 8), rsErrorSec);
        // 带宽对外是 Mbps，带小数，不能用整数单元格
        setNumberOrDash(row.getCell(start + 9), totalBandwidth);
        setNumberOrDash(row.getCell(start + 10), usedBandwidth);
        setNumberOrDash(row.getCell(start + 11), bandwidthUsage);
    }

    private void writeThresholdSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("说明");
        CellStyle headStyle = headerStyle(workbook);
        CellStyle bodyStyle = bodyStyle(workbook);

        Row header = sheet.createRow(0);
        String[] columns = {"速率", "光模块类型", "发光过高门限", "发光过低门限", "收光过高门限", "收光过低门限"};
        for (int i = 0; i < columns.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns[i]);
            cell.setCellStyle(headStyle);
        }

        List<ThresholdService.RuleView> views = thresholdService.listRuleViews();
        int groupStart = 1;
        for (int i = 0; i < views.size(); i++) {
            ThresholdService.RuleView view = views.get(i);
            int rowIdx = i + 1;
            ThresholdService.Range range = view.range();

            Row row = sheet.createRow(rowIdx);
            for (int c = 0; c < columns.length; c++) {
                row.createCell(c).setCellStyle(bodyStyle);
            }
            row.getCell(1).setCellValue(view.matchKey());
            row.getCell(2).setCellValue(range.txHigh());
            row.getCell(3).setCellValue(range.txLow());
            row.getCell(4).setCellValue(range.rxHigh());
            row.getCell(5).setCellValue(range.rxLow());

            boolean groupEnds = i == views.size() - 1 || !views.get(i + 1).rate().equals(view.rate());
            if (groupEnds) {
                sheet.getRow(groupStart).getCell(0).setCellValue(view.rate());
                if (rowIdx > groupStart) {
                    sheet.addMergedRegion(new CellRangeAddress(groupStart, rowIdx, 0, 0));
                }
                groupStart = rowIdx + 1;
            }
        }
        autoSizeColumns(sheet, columns.length);
    }

    // ========== 导出辅助 ==========

    private static Row styledRow(Sheet sheet, int rowIdx, CellStyle style) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < EXPORT_COLUMNS; i++) {
            row.createCell(i).setCellStyle(style);
        }
        return row;
    }

    private static void mergeVertically(Sheet sheet, int firstRow, int lastRow) {
        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, 0, 0));
        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, 1, 1));
    }

    private static void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.min(width + 512, 12000));
        }
    }

    /** 网元类型以纯数字呈现时写入数值单元格，否则写原文 */
    private static void setNeType(Cell cell, String typeName) {
        if (typeName == null || typeName.isBlank()) {
            cell.setCellValue(EMPTY_CELL);
            return;
        }
        try {
            cell.setCellValue(Double.parseDouble(typeName));
        } catch (NumberFormatException e) {
            cell.setCellValue(typeName);
        }
    }

    private static void setStatus(Cell cell, String status, CellStyle warnStyle) {
        if (status == null || status.isBlank() || ThresholdService.STATUS_NORMAL.equals(status)) {
            cell.setCellValue(status == null || status.isBlank() ? EMPTY_CELL : status);
            return;
        }
        cell.setCellValue(status);
        cell.setCellStyle(warnStyle);
    }

    private static void setNumberOrDash(Cell cell, Double value) {
        if (value == null) {
            cell.setCellValue(EMPTY_CELL);
        } else {
            cell.setCellValue(value);
        }
    }

    private static void setIntegerOrDash(Cell cell, Integer value) {
        if (value == null) {
            cell.setCellValue(EMPTY_CELL);
        } else {
            cell.setCellValue(value);
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = bordered(workbook);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setWrapText(true);
        return style;
    }

    private static CellStyle bodyStyle(Workbook workbook) {
        return bordered(workbook);
    }

    private static CellStyle warnStyle(Workbook workbook) {
        CellStyle style = bordered(workbook);
        Font font = workbook.createFont();
        font.setColor(IndexedColors.RED.getIndex());
        style.setFont(font);
        return style;
    }

    private static CellStyle bordered(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
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
        String cron = (String) body.getOrDefault("cronExpression", InspectionScheduler.DEFAULT_CRON);
        inspectionScheduler.updateConfig(enabled, scope, network, cron);
        auditService.record("CONFIG", "定时巡检", "SUCCESS", "enabled=" + enabled + " cron=" + cron);
        return inspectionScheduler.getStatus();
    }

    // ========== 采集参数 ==========

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
        boolean saveInvalid = !Boolean.FALSE.equals(body.get("saveInvalid"));
        inspectionService.updateCollectParams(concurrency, maxRounds, autoConnect, autoDisconnect, saveInvalid);
        auditService.record("CONFIG", "采集参数", "SUCCESS", "concurrency=" + concurrency);
        return inspectionService.getCollectParams();
    }

    // ========== 门限管理 ==========

    /**
     * 查询所有门限规则（含预置标准值）
     */
    @GetMapping("/thresholds")
    public List<Map<String, Object>> listThresholds() {
        return thresholdService.listRulesWithPresets();
    }

    /**
     * 更新门限规则（只允许修改，不允许新增或删除）
     */
    @PostMapping("/thresholds")
    public ThresholdRule saveThreshold(@RequestBody ThresholdRule rule) {
        ThresholdRule saved = thresholdService.updateRule(rule.getMatchKey(), rule);
        auditService.record("THRESHOLD", rule.getMatchKey(), "SUCCESS", null);
        return saved;
    }

    // ========== 审计日志 ==========

    @GetMapping("/audit/logs")
    public List<AuditLog> getAuditLogs() {
        return auditService.getRecentLogs();
    }

    // ========== 数据备份/恢复 ==========

    private static final long MAX_RESTORE_SIZE = 100 * 1024 * 1024; // 100MB

    private boolean checkAdminAuth(HttpServletRequest request) {
        if (adminToken == null || adminToken.isEmpty()) {
            log.warn("app.admin-token 未配置，备份/恢复功能已禁用");
            return false;
        }
        String token = request.getHeader("X-Admin-Token");
        return adminToken.equals(token);
    }

    /**
     * 备份 SQLite 数据库
     */
    @GetMapping("/backup")
    public void backupDatabase(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!checkAdminAuth(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return;
        }
        Path dbPath = sqliteDbPath();
        if (!Files.exists(dbPath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "数据库文件不存在");
            return;
        }
        String timestamp = LocalDateTime.now().format(FILE_TIME_FMT);
        String filename = "qx_inspection_backup_" + timestamp + ".db";
        String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"backup.db\"; filename*=UTF-8''" + encodedName);
        Path snapshot = null;
        try {
            // WAL 模式下直接拷主库文件会漏掉尚未 checkpoint 的事务，改用 SQLite 自写的一致性快照
            snapshot = snapshotDatabase();
            Files.copy(snapshot, response.getOutputStream());
            response.getOutputStream().flush();
            auditService.record("BACKUP", AUDIT_TARGET_SQLITE, "SUCCESS", filename);
        } catch (Exception e) {
            log.error("backup failed", e);
            auditService.record("BACKUP", AUDIT_TARGET_SQLITE, "FAIL", e.getMessage());
        } finally {
            deleteQuietly(snapshot);
        }
    }

    /**
     * 恢复 SQLite 数据库
     */
    @PostMapping("/restore")
    public Map<String, Object> restoreDatabase(HttpServletRequest request,
                                               @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (!checkAdminAuth(request)) {
            return Map.of("success", false, "message", "需要管理员权限");
        }
        if (file.isEmpty()) {
            return Map.of("success", false, "message", "上传文件不能为空");
        }
        if (file.getSize() > MAX_RESTORE_SIZE) {
            return Map.of("success", false, "message", "文件大小不能超过100MB");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.endsWith(".db")) {
            return Map.of("success", false, "message", "仅支持 .db 文件");
        }
        // 校验文件头是否为 SQLite 格式（前16字节应包含 "SQLite format 3"）
        try (var in = file.getInputStream()) {
            byte[] header = new byte[16];
            int read = in.read(header);
            if (read < 16) {
                return Map.of("success", false, "message", "文件太小，不是有效的 SQLite 数据库");
            }
            String headerStr = new String(header, StandardCharsets.US_ASCII);
            if (!headerStr.contains("SQLite format 3")) {
                return Map.of("success", false, "message", "文件格式不正确，不是有效的 SQLite 数据库");
            }
        } catch (IOException e) {
            return Map.of("success", false, "message", "文件读取失败: " + e.getMessage());
        }

        Path dbPath = sqliteDbPath();
        Path incoming = null;
        try {
            // 先落到临时文件并校验表结构，确认是本工具的库之后再覆盖活库
            incoming = Files.createTempFile("qx_restore_", ".db");
            Files.copy(file.getInputStream(), incoming, StandardCopyOption.REPLACE_EXISTING);
            if (!hasRequiredTables(incoming)) {
                return Map.of("success", false, "message", "文件中缺少必需的数据表，不是本工具的数据库备份");
            }

            Path dbDir = dbPath.getParent();
            if (dbDir != null && !Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
            }

            // 覆盖前先留存当前库的一致性快照
            if (Files.exists(dbPath)) {
                Path backupPath = dbDir.resolve(PRE_RESTORE_BACKUP);
                try {
                    Path prev = snapshotDatabase();
                    try {
                        Files.copy(prev, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        deleteQuietly(prev);
                    }
                } catch (Exception e) {
                    log.warn("pre-restore snapshot failed: {}", e.getMessage());
                }
            }

            Files.copy(incoming, dbPath, StandardCopyOption.REPLACE_EXISTING);

            // -wal / -shm 属于被覆盖掉的旧库，残留会在下次连接时回放到新库上，必须删除
            deleteQuietly(dbPath.resolveSibling(dbPath.getFileName() + "-wal"));
            deleteQuietly(dbPath.resolveSibling(dbPath.getFileName() + "-shm"));

            auditService.record("RESTORE", AUDIT_TARGET_SQLITE, "SUCCESS", originalName);
            return Map.of("success", true, "message", "数据库恢复成功，请重启应用");
        } catch (Exception e) {
            log.error("restore database failed", e);
            auditService.record("RESTORE", AUDIT_TARGET_SQLITE, "FAIL", e.getMessage());
            return Map.of("success", false, "message", "恢复失败: " + e.getMessage());
        } finally {
            deleteQuietly(incoming);
        }
    }

    /**
     * SQLite 库文件路径，从数据源 URL 解析，避免与 spring.datasource.sqlite.url 配置漂移
     */
    private Path sqliteDbPath() {
        String path = sqliteUrl.startsWith(JDBC_SQLITE_PREFIX)
                ? sqliteUrl.substring(JDBC_SQLITE_PREFIX.length())
                : sqliteUrl;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return Path.of(path);
    }

    /**
     * 用 VACUUM INTO 生成一份完整副本：WAL 模式下未 checkpoint 的事务也能包含进去
     */
    private Path snapshotDatabase() throws IOException {
        Path snapshot = Files.createTempFile("qx_snapshot_", ".db");
        Files.delete(snapshot); // VACUUM INTO 要求目标文件不存在
        String target = toSqlitePath(snapshot);
        sqliteJdbc.execute("VACUUM INTO '" + target + "'");
        return snapshot;
    }

    /** SQLite 在 Windows 上更接受正斜杠路径；顺便转义单引号 */
    private static String toSqlitePath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    /**
     * 校验库中是否包含本工具必需的几张表
     */
    private boolean hasRequiredTables(Path dbFile) {
        String placeholders = String.join(",", java.util.Collections.nCopies(REQUIRED_TABLES.size(), "?"));
        String sql = "SELECT COUNT(DISTINCT name) FROM sqlite_master WHERE type='table' AND name IN ("
                + placeholders + ")";
        try (Connection conn = DriverManager.getConnection(JDBC_SQLITE_PREFIX + toSqlitePath(dbFile));
             var stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < REQUIRED_TABLES.size(); i++) {
                stmt.setString(i + 1, REQUIRED_TABLES.get(i));
            }
            try (var rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) == REQUIRED_TABLES.size();
            }
        } catch (Exception e) {
            log.warn("failed to verify restored database: {}", e.getMessage());
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("failed to delete temp file {}: {}", path, e.getMessage());
        }
    }
}
