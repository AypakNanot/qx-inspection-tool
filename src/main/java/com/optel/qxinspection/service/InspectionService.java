package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.mysql.Dmeo;
import com.optel.qxinspection.entity.mysql.DmNe;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import com.optel.qxinspection.qx.QxCommandService;
import com.optel.qxinspection.qx.message.LaserAttributeResponse;
import com.optel.qxinspection.qx.message.PortRecord;
import com.optel.qxinspection.repository.mysql.DmeoRepository;
import com.optel.qxinspection.repository.mysql.DmNeRepository;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import com.optel.qxinspection.repository.sqlite.InspectionRoundRepository;
import com.optel.qxinspection.repository.sqlite.OpticalPowerInspectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final QxCommandService qxCommandService;
    private final QxConnectionService qxConnectionService;
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;
    private final InspectionRoundRepository inspectionRoundRepository;
    private final OpticalPowerInspectionRepository powerRecordRepository;
    private final DmNeRepository dmNeRepository;
    private final DmeoRepository dmeoRepository;
    private final ThresholdService thresholdService;

    @Value("${app.inspection.concurrency:10}")
    private int concurrency;

    @Value("${app.inspection.max-rounds:10}")
    private int maxRounds;

    @Value("${app.inspection.port-defname-patterns:STM%,GE%}")
    private String portDefnamePatterns;

    @jakarta.annotation.PostConstruct
    public void init() {
        thresholdService.initDefaultGlobalRule();
    }

    /** 当前运行中的轮次（用于进度查询） */
    private volatile InspectionRound currentRound;
    private final AtomicInteger progressCurrent = new AtomicInteger(0);
    private final List<String> progressFailures = new CopyOnWriteArrayList<>();
    private volatile String progressCurrentNe = "";

    /**
     * 手动触发巡检（全网）
     */
    public InspectionRound triggerInspectionAll() {
        return triggerInspection("ALL", null, "MANUAL");
    }

    /**
     * 按网络触发巡检
     */
    public InspectionRound triggerInspectionByNetwork(String networkName) {
        return triggerInspection("NETWORK", networkName, "MANUAL");
    }

    /**
     * 按单个网元触发巡检
     */
    public InspectionRound triggerInspectionByNe(String neId) {
        return triggerInspection("SINGLE", neId, "MANUAL");
    }

    /**
     * 定时触发巡检（内部用）
     */
    public InspectionRound triggerScheduledInspection(String scopeType, String scopeParam) {
        return triggerInspection(scopeType, scopeParam, "SCHEDULED");
    }

    /**
     * 获取当前巡检进度
     */
    public synchronized Map<String, Object> getProgress() {
        Map<String, Object> progress = new LinkedHashMap<>();
        InspectionRound round = currentRound;
        if (round == null) {
            progress.put("running", false);
            return progress;
        }
        progress.put("running", "RUNNING".equals(round.getStatus()));
        progress.put("roundId", round.getId());
        progress.put("total", round.getTotalCount());
        progress.put("done", progressCurrent.get());
        progress.put("failures", progressFailures.size());
        progress.put("failures_list", new ArrayList<>(progressFailures));
        progress.put("currentNe", progressCurrentNe);
        return progress;
    }

    /**
     * 查询最新轮次的巡检结果
     */
    public List<OpticalPowerInspection> getLatestResults(String network) {
        List<OpticalPowerInspection> results = inspectionRoundRepository.findFirstByOrderByStartTimeDesc()
                .map(r -> {
                    if (network != null && !network.isEmpty()) {
                        return powerRecordRepository.findByRoundIdAndNetworkName(r.getId(), network);
                    }
                    return powerRecordRepository.findByRoundId(r.getId());
                })
                .orElse(Collections.emptyList());
        return thresholdService.applyThresholds(results);
    }

    /**
     * 查询指定轮次的巡检结果
     */
    public List<OpticalPowerInspection> getResultsByRound(Long roundId, String network) {
        List<OpticalPowerInspection> results;
        if (network != null && !network.isEmpty()) {
            results = powerRecordRepository.findByRoundIdAndNetworkName(roundId, network);
        } else {
            results = powerRecordRepository.findByRoundId(roundId);
        }
        return thresholdService.applyThresholds(results);
    }

    /**
     * 查询指定网元的巡检结果（最新轮次）
     */
    public List<OpticalPowerInspection> getResultsByNe(String neId) {
        List<OpticalPowerInspection> results = inspectionRoundRepository.findFirstByOrderByStartTimeDesc()
                .map(r -> powerRecordRepository.findByRoundIdAndNeId(r.getId(), neId))
                .orElse(Collections.emptyList());
        return thresholdService.applyThresholds(results);
    }

    /**
     * 获取巡检轮次列表
     */
    public List<InspectionRound> listRounds() {
        return inspectionRoundRepository.findAll();
    }

    /**
     * 获取单端口历史趋势
     */
    public List<OpticalPowerInspection> getPortTrend(String neId, int slotNo, int portNo) {
        return powerRecordRepository.findTrendByPort(neId, slotNo, portNo);
    }

    /**
     * 获取网元历史趋势（所有端口）
     */
    public List<OpticalPowerInspection> getNeTrend(String neId) {
        return powerRecordRepository.findTrendByNe(neId);
    }

    /**
     * 获取越限异常汇总（按网元分组）- 门限实时计算
     */
    public List<Map<String, Object>> getAnomalySummary(Long roundId) {
        InspectionRound round;
        if (roundId != null) {
            round = inspectionRoundRepository.findById(roundId).orElse(null);
        } else {
            round = inspectionRoundRepository.findFirstByOrderByStartTimeDesc().orElse(null);
        }
        if (round == null) return Collections.emptyList();

        List<OpticalPowerInspection> all = thresholdService.applyThresholds(
                powerRecordRepository.findByRoundId(round.getId()));

        // 按网元分组统计越限
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (OpticalPowerInspection r : all) {
            boolean over = (r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0)
                    || (r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0);
            if (!over) continue;

            String key = r.getNeId();
            grouped.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("neId", r.getNeId());
                m.put("neName", r.getNeName());
                m.put("overThresholdCount", 0L);
                return m;
            });
            Map<String, Object> m = grouped.get(key);
            m.put("overThresholdCount", (long) m.get("overThresholdCount") + 1);
        }

        List<Map<String, Object>> result = new ArrayList<>(grouped.values());
        result.sort((a, b) -> Long.compare(
                (long) b.get("overThresholdCount"), (long) a.get("overThresholdCount")));
        return result;
    }

    /**
     * 获取越限详细记录 - 门限实时计算
     */
    public List<OpticalPowerInspection> getOverThresholdRecords(Long roundId) {
        InspectionRound round;
        if (roundId != null) {
            round = inspectionRoundRepository.findById(roundId).orElse(null);
        } else {
            round = inspectionRoundRepository.findFirstByOrderByStartTimeDesc().orElse(null);
        }
        if (round == null) return Collections.emptyList();

        List<OpticalPowerInspection> all = thresholdService.applyThresholds(
                powerRecordRepository.findByRoundId(round.getId()));
        return all.stream()
                .filter(r -> (r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0)
                        || (r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0))
                .toList();
    }

    /**
     * 获取巡检摘要统计（含劣化/过载按类型分组）
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        InspectionRound latest = inspectionRoundRepository.findFirstByOrderByStartTimeDesc().orElse(null);
        if (latest == null) {
            summary.put("hasData", false);
            return summary;
        }
        summary.put("hasData", true);
        summary.put("roundId", latest.getId());
        summary.put("startTime", latest.getStartTime());
        summary.put("endTime", latest.getEndTime());
        summary.put("status", latest.getStatus());
        summary.put("totalDevices", latest.getTotalCount());
        summary.put("doneDevices", latest.getDoneCount());
        summary.put("failDevices", latest.getFailCount());

        List<OpticalPowerInspection> records = thresholdService.applyThresholds(
                powerRecordRepository.findByRoundId(latest.getId()));
        List<OpticalPowerInspection> supported = records.stream()
                .filter(r -> Boolean.TRUE.equals(r.getSupported())).toList();

        long overThreshold = supported.stream()
                .filter(r -> (r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0)
                        || (r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0))
                .count();

        summary.put("totalPorts", records.size());
        summary.put("supportedPorts", supported.size());
        summary.put("overThresholdPorts", overThreshold);

        // 按模块类型分组统计
        Map<String, Map<String, Object>> byModuleType = new LinkedHashMap<>();
        for (OpticalPowerInspection r : supported) {
            String key = r.getModuleTypeKey() != null ? r.getModuleTypeKey() : "Unknown";
            byModuleType.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("count", 0L);
                m.put("overThreshold", 0L);
                return m;
            });
            Map<String, Object> m = byModuleType.get(key);
            m.put("count", (long) m.get("count") + 1);
            if ((r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0)
                    || (r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0)) {
                m.put("overThreshold", (long) m.get("overThreshold") + 1);
            }
        }
        summary.put("byModuleType", byModuleType);

        // 按设备类型分组统计（使用 neId 查询 MySQL 获取设备类型）
        Map<String, Map<String, Object>> byDeviceType = new LinkedHashMap<>();
        for (OpticalPowerInspection r : records) {
            String neName = r.getNeName() != null ? r.getNeName() : "Unknown";
            // 从 neName 提取设备类型（括号前的部分）
            String deviceType = neName.contains("(") ? neName.substring(0, neName.indexOf("(")) : neName;
            if (deviceType.isEmpty()) deviceType = "Unknown";
            byDeviceType.computeIfAbsent(deviceType, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("totalPorts", 0L);
                m.put("supportedPorts", 0L);
                m.put("overThreshold", 0L);
                return m;
            });
            Map<String, Object> m = byDeviceType.get(deviceType);
            m.put("totalPorts", (long) m.get("totalPorts") + 1);
            if (Boolean.TRUE.equals(r.getSupported())) {
                m.put("supportedPorts", (long) m.get("supportedPorts") + 1);
                if ((r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0)
                        || (r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0)) {
                    m.put("overThreshold", (long) m.get("overThreshold") + 1);
                }
            }
        }
        summary.put("byDeviceType", byDeviceType);

        return summary;
    }

    // ========== 内部实现 ==========

    private synchronized InspectionRound triggerInspection(String scopeType, String scopeParam, String triggerType) {
        // 检查是否有正在运行的巡检
        if (currentRound != null && "RUNNING".equals(currentRound.getStatus())) {
            throw new IllegalStateException("已有巡检任务正在运行，请等待完成后再触发");
        }

        // 创建轮次
        InspectionRound round = new InspectionRound();
        round.setTriggerType(triggerType);
        round.setScopeType(scopeType);
        round.setScopeParam(scopeParam);
        InspectionRound saved = inspectionRoundRepository.save(round);

        // 解析目标设备列表
        List<DeviceAccessConfig> targets = resolveTargets(scopeType, scopeParam);
        saved.setTotalCount(targets.size());
        inspectionRoundRepository.save(saved);

        // 重置进度
        currentRound = saved;
        progressCurrent.set(0);
        progressFailures.clear();
        progressCurrentNe = "";

        // 异步执行巡检（使用专用线程池，捕获异常防止轮次卡在RUNNING）
        final InspectionRound finalRound = saved;
        ExecutorService inspectionPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "inspection-main");
            t.setDaemon(true);
            return t;
        });
        CompletableFuture.runAsync(() -> executeInspection(finalRound, targets), inspectionPool)
                .exceptionally(ex -> {
                    log.error("巡检执行异常: roundId={}, {}", finalRound.getId(), ex.getMessage(), ex);
                    finalRound.setStatus("FAILED");
                    finalRound.setEndTime(LocalDateTime.now());
                    inspectionRoundRepository.save(finalRound);
                    return null;
                })
                .thenRun(inspectionPool::shutdown);

        return saved;
    }

    private List<DeviceAccessConfig> resolveTargets(String scopeType, String scopeParam) {
        List<DeviceAccessConfig> all = deviceAccessConfigRepository.findAll();
        return switch (scopeType) {
            case "NETWORK" -> all.stream()
                    .filter(d -> scopeParam.equals(d.getNetworkName()))
                    .toList();
            case "SINGLE" -> all.stream()
                    .filter(d -> scopeParam.equals(d.getNeId()))
                    .toList();
            default -> all; // ALL
        };
    }

    private void executeInspection(InspectionRound round, List<DeviceAccessConfig> targets) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            AtomicInteger doneCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (DeviceAccessConfig device : targets) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        progressCurrentNe = device.getNeName() + "(" + device.getIpAddr() + ")";
                        inspectDevice(round, device);
                        doneCount.incrementAndGet();
                    } catch (Exception e) {
                        log.error("巡检设备失败: {}({}), {}", device.getNeName(), device.getIpAddr(), e.getMessage());
                        failCount.incrementAndGet();
                        progressFailures.add(device.getNeName() + "(" + device.getIpAddr() + ")");
                    } finally {
                        progressCurrent.incrementAndGet();
                    }
                }, pool);
                futures.add(future);
            }

            // 等待全部完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 更新轮次状态
        round.setDoneCount(doneCount.get());
        round.setFailCount(failCount.get());
        round.setStatus("COMPLETED");
        round.setEndTime(LocalDateTime.now());
        inspectionRoundRepository.save(round);

        // 清理超龄轮次
        cleanupOldRounds();

        log.info("巡检完成: roundId={}, 总设备={}, 成功={}, 失败={}",
                round.getId(), round.getTotalCount(), round.getDoneCount(), round.getFailCount());
        } finally {
            pool.shutdown();
        }
    }

    private void inspectDevice(InspectionRound round, DeviceAccessConfig device) {
        String ip = device.getIpAddr();
        int port = qxConnectionService.getEffectivePort(device);
        String user = device.getUsername();
        String password = device.getPassword();

        // 确保设备已连接（未连接则先建立连接）
        if (!qxConnectionService.isConnected(device.getNeId())) {
            log.debug("设备未连接，尝试建立连接: {}", device.getNeName());
            boolean connected = qxConnectionService.connectSingle(device.getNeId());
            if (!connected) {
                throw new RuntimeException("设备连接失败: " + device.getNeName());
            }
        }

        // 从 dmeo 表查询该网元下的光口端口（替代 0x2406）
        List<Dmeo> opticalPorts = queryOpticalPorts(device.getNeId());
        if (opticalPorts.isEmpty()) {
            log.debug("设备无光口端口: {}", device.getNeName());
            return;
        }

        // 逐端口查询激光器属性 0x2410
        List<OpticalPowerInspection> records = new ArrayList<>();
        int failPorts = 0;

        for (Dmeo dmeoPort : opticalPorts) {
            try {
                // 从 dmeo oid 解析 0x2410 所需参数
                int slotId = parseSlotId(dmeoPort.getOid());
                int portId = parsePortId(dmeoPort.getOid());
                int portType = dmeoPort.getType() != null ? dmeoPort.getType() : 0xFF;

                LaserAttributeResponse laser = qxCommandService.queryLaserAttribute(
                        ip, port, user, password,
                        slotId, portType, 0xFF, portId);

                // 构建 PortRecord 用于 buildRecord
                PortRecord p = new PortRecord();
                p.setSubcaseNo(1);
                p.setSlotId(slotId);
                p.setPortType(portType);
                p.setPortSubType(0xFF);
                p.setPortId(portId);

                OpticalPowerInspection record = buildRecord(round, device, p, laser);
                records.add(record);
            } catch (Exception e) {
                failPorts++;
                // 记录失败端口
                PortRecord failP = new PortRecord();
                failP.setSlotId(parseSlotId(dmeoPort.getOid()));
                failP.setPortType(dmeoPort.getType() != null ? dmeoPort.getType() : 0xFF);
                failP.setPortSubType(0xFF);
                failP.setPortId(parsePortId(dmeoPort.getOid()));
                records.add(buildFailRecord(round, device, failP, e.getMessage()));
                log.debug("端口查询失败: {} oid={}, {}",
                        device.getNeName(), dmeoPort.getOid(), e.getMessage());
            }
        }

        // 批量保存
        if (!records.isEmpty()) {
            powerRecordRepository.saveAll(records);
        }

        log.debug("设备巡检完成: {}, 光口数={}, 失败={}", device.getNeName(), opticalPorts.size(), failPorts);
    }

    /**
     * 从 dmeo 表查询该网元下符合 defName 模式的光口端口
     */
    private List<Dmeo> queryOpticalPorts(String neId) {
        String[] patterns = parsePatterns();
        // neId 对应 dmeo 的 oid 前缀（第一段），用 LIKE 匹配
        return dmeoRepository.findByNeOidAndDefNamePatterns(
                5, neId,
                patterns[0], patterns[1], patterns[2], patterns[3], patterns[4]);
    }

    private static final int MAX_PATTERNS = 5;

    private String[] parsePatterns() {
        String[] raw = portDefnamePatterns.split(",");
        String[] result = new String[MAX_PATTERNS];
        for (int i = 0; i < MAX_PATTERNS; i++) {
            result[i] = i < raw.length ? raw[i].trim() : null;
        }
        return result;
    }

    /**
     * 从 dmeo oid 解析盘号（slotId）
     * oid 格式: "neOid:slotOid:portOid" 或更深层级
     */
    private int parseSlotId(String oid) {
        if (oid == null || oid.isEmpty()) {
            return 1;
        }
        String[] parts = oid.split(":");
        return parts.length >= 2 ? parseIntSafe(parts[1]) : 1;
    }

    /**
     * 从 dmeo oid 解析端口号（portId）
     * oid 格式: "neOid:slotOid:portOid" 或更深层级
     */
    private int parsePortId(String oid) {
        if (oid == null || oid.isEmpty()) {
            return 0xFFFF;
        }
        String[] parts = oid.split(":");
        return parts.length >= 3 ? parseIntSafe(parts[2]) : 0xFFFF;
    }

    private int parseIntSafe(String s) {
        try {
            // oid 段可能含点分（如 "1.3.6"），取最后一段数字
            int lastDot = s.lastIndexOf('.');
            String num = lastDot >= 0 ? s.substring(lastDot + 1) : s;
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private OpticalPowerInspection buildRecord(InspectionRound round, DeviceAccessConfig device,
                                                PortRecord port, LaserAttributeResponse laser) {
        OpticalPowerInspection r = new OpticalPowerInspection();
        r.setRoundId(round.getId());
        r.setNeId(device.getNeId());
        r.setNeName(device.getNeName());
        r.setNetworkName(device.getNetworkName());
        r.setNeTypeName(device.getNeTypeName());
        r.setSlotNo(port.getSlotId());
        r.setPortNo(port.getPortId());
        r.setPortType(port.getPortType());
        r.setPortSubType(port.getPortSubType());
        r.setInspectionTime(LocalDateTime.now());

        if (laser == null) {
            r.setSupported(false);
            r.setFailReason("激光器查询无响应");
            return r;
        }

        r.setSupported(laser.isSupported());
        if (!laser.isSupported()) {
            return r;
        }

        r.setLaserType(laser.getLaserTypeName());
        r.setLaserDistance(laser.getDistanceName());
        r.setModuleTypeKey(laser.getModuleTypeKey());
        r.setPartNumber(laser.getPartNumber());
        r.setLaserWave(laser.getLaserWaveName());
        r.setTxPower((double) laser.getTranLaserPower());
        r.setRxPower((double) laser.getRecvLaserPower());

        return r;
    }

    private OpticalPowerInspection buildFailRecord(InspectionRound round, DeviceAccessConfig device,
                                                    PortRecord port, String reason) {
        OpticalPowerInspection r = new OpticalPowerInspection();
        r.setRoundId(round.getId());
        r.setNeId(device.getNeId());
        r.setNeName(device.getNeName());
        r.setNetworkName(device.getNetworkName());
        r.setNeTypeName(device.getNeTypeName());
        r.setSlotNo(port.getSlotId());
        r.setPortNo(port.getPortId());
        r.setPortType(port.getPortType());
        r.setPortSubType(port.getPortSubType());
        r.setSupported(false);
        r.setFailReason(reason);
        r.setInspectionTime(LocalDateTime.now());
        return r;
    }


    private void cleanupOldRounds() {
        try {
            List<InspectionRound> all = inspectionRoundRepository.findAll();
            if (all.size() > maxRounds) {
                all.sort(Comparator.comparing(InspectionRound::getStartTime,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed());
                for (int i = maxRounds; i < all.size(); i++) {
                    InspectionRound old = all.get(i);
                    List<OpticalPowerInspection> oldRecords = powerRecordRepository.findByRoundId(old.getId());
                    if (!oldRecords.isEmpty()) {
                        powerRecordRepository.deleteAll(oldRecords);
                    }
                    inspectionRoundRepository.delete(old);
                }
            }
        } catch (Exception e) {
            log.warn("清理超龄轮次失败: {}", e.getMessage());
        }
    }
}
