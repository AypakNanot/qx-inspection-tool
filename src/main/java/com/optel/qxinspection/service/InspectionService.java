package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.mysql.DmNe;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import com.optel.qxinspection.qx.QxCommandService;
import com.optel.qxinspection.qx.message.LaserAttributeResponse;
import com.optel.qxinspection.qx.message.PortRecord;
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
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;
    private final InspectionRoundRepository inspectionRoundRepository;
    private final OpticalPowerInspectionRepository powerRecordRepository;
    private final DmNeRepository dmNeRepository;
    private final ThresholdService thresholdService;

    @Value("${app.inspection.concurrency:10}")
    private int concurrency;

    @Value("${app.inspection.max-rounds:10}")
    private int maxRounds;

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
        int port = device.getPort() != null ? device.getPort() : 9900;
        String user = device.getUsername();
        String password = device.getPassword();

        // 查询端口列表
        List<PortRecord> ports = qxCommandService.queryPorts(ip, port, user, password);
        if (ports.isEmpty()) {
            log.debug("设备无端口: {}", device.getNeName());
            return;
        }

        // 逐端口查询激光器属性
        List<OpticalPowerInspection> records = new ArrayList<>();
        int failPorts = 0;

        for (PortRecord p : ports) {
            try {
                LaserAttributeResponse laser = qxCommandService.queryLaserAttribute(
                        ip, port, user, password,
                        p.getSubcaseNo(), p.getPortType(), p.getPortSubType(), p.getPortId());

                OpticalPowerInspection record = buildRecord(round, device, p, laser);
                records.add(record);
            } catch (Exception e) {
                failPorts++;
                // 记录失败的端口
                OpticalPowerInspection record = buildFailRecord(round, device, p, e.getMessage());
                records.add(record);
                log.debug("端口查询失败: {} slot={} port={}, {}",
                        device.getNeName(), p.getSlotId(), p.getPortId(), e.getMessage());
            }
        }

        // 批量保存
        if (!records.isEmpty()) {
            powerRecordRepository.saveAll(records);
        }

        log.debug("设备巡检完成: {}, 端口数={}, 失败={}", device.getNeName(), ports.size(), failPorts);
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
