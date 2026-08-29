package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import com.optel.qxinspection.laser.LaserAttributeAckData;
import com.optel.qxinspection.laser.LaserAttributeGetData;
import com.optel.qxinspection.laser.service.ILaserService;
import com.optel.qxinspection.util.OidUtil;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import com.optel.qxinspection.repository.sqlite.InspectionRoundRepository;
import com.optel.qxinspection.repository.sqlite.OpticalPowerInspectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final ILaserService laserService;
    private final QxConnectionService qxConnectionService;
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;
    private final InspectionRoundRepository inspectionRoundRepository;
    private final OpticalPowerInspectionRepository powerRecordRepository;
    @Qualifier("sqliteJdbc")
    private final JdbcTemplate sqliteJdbc;
    private final ThresholdService thresholdService;
    private final SysConfigService sysConfigService;

    private static final String KEY_CONCURRENCY = "collect.concurrency";
    private static final String KEY_MAX_ROUNDS = "collect.maxRounds";
    private static final String KEY_AUTO_CONNECT = "inspect.autoConnect";
    private static final String KEY_AUTO_DISCONNECT = "inspect.autoDisconnect";
    private static final String KEY_SAVE_INVALID = "inspect.saveInvalid";

    @Value("${app.inspection.concurrency:10}")
    private int concurrency;

    @Value("${app.inspection.max-rounds:10}")
    private int maxRounds;

    @Value("${app.inspection.port-defname-patterns:STM%,GE%}")
    private String portDefnamePatterns;

    @jakarta.annotation.PostConstruct
    public void init() {
        thresholdService.initDefaultGlobalRule();
        // 从数据库加载采集参数（覆盖@Value默认值）
        concurrency = Integer.parseInt(sysConfigService.get(KEY_CONCURRENCY, String.valueOf(concurrency)));
        maxRounds = Integer.parseInt(sysConfigService.get(KEY_MAX_ROUNDS, String.valueOf(maxRounds)));
        autoConnect = Boolean.parseBoolean(sysConfigService.get(KEY_AUTO_CONNECT, "true"));
        autoDisconnect = Boolean.parseBoolean(sysConfigService.get(KEY_AUTO_DISCONNECT, "true"));
        saveInvalid = Boolean.parseBoolean(sysConfigService.get(KEY_SAVE_INVALID, "true"));
        log.info("采集参数加载: concurrency={}, maxRounds={}, autoConnect={}, autoDisconnect={}, saveInvalid={}",
                concurrency, maxRounds, autoConnect, autoDisconnect, saveInvalid);
    }

    /** 是否巡检时自动连接未在线设备 */
    private volatile boolean autoConnect = true;
    /** 是否巡检完成后自动断开所有连接 */
    private volatile boolean autoDisconnect = true;
    /** 是否保存无效记录到数据库 */
    private volatile boolean saveInvalid = true;

    /** 当前运行中的轮次（用于进度查询） */
    private volatile InspectionRound currentRound;
    private final AtomicInteger progressCurrent = new AtomicInteger(0);
    private final List<Map<String, String>> progressFailures = new CopyOnWriteArrayList<>();
    private volatile String progressCurrentNe = "";
    private volatile String progressCurrentPort = "";

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
        progress.put("currentPort", progressCurrentPort);
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
     * 对比两次巡检结果，返回变化的端口列表
     */
    public Map<String, Object> compareRounds(Long roundA, Long roundB) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<OpticalPowerInspection> dataA = thresholdService.applyThresholds(
                powerRecordRepository.findByRoundId(roundA));
        List<OpticalPowerInspection> dataB = thresholdService.applyThresholds(
                powerRecordRepository.findByRoundId(roundB));

        Map<String, OpticalPowerInspection> mapA = new LinkedHashMap<>();
        for (OpticalPowerInspection r : dataA) {
            mapA.put(r.getNeId() + ":" + r.getSlotNo() + ":" + r.getPortNo(), r);
        }
        Map<String, OpticalPowerInspection> mapB = new LinkedHashMap<>();
        for (OpticalPowerInspection r : dataB) {
            mapB.put(r.getNeId() + ":" + r.getSlotNo() + ":" + r.getPortNo(), r);
        }

        List<Map<String, Object>> changes = new ArrayList<>();

        for (Map.Entry<String, OpticalPowerInspection> entry : mapB.entrySet()) {
            String key = entry.getKey();
            OpticalPowerInspection rb = entry.getValue();
            OpticalPowerInspection ra = mapA.get(key);

            if (ra == null) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("type", "new");
                change.put("neName", rb.getNeName());
                change.put("portName", rb.getPortName());
                change.put("txPower", rb.getTxPower());
                change.put("rxPower", rb.getRxPower());
                change.put("status", getPortStatusText(rb));
                changes.add(change);
                continue;
            }

            double txDelta = safeDelta(rb.getTxPower(), ra.getTxPower());
            double rxDelta = safeDelta(rb.getRxPower(), ra.getRxPower());
            boolean statusChanged = !Objects.equals(getPortStatusText(ra), getPortStatusText(rb));

            if (Math.abs(txDelta) > 0.5 || Math.abs(rxDelta) > 0.5 || statusChanged) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("type", statusChanged ? "status_change" : "power_change");
                change.put("neName", rb.getNeName());
                change.put("portName", rb.getPortName());
                change.put("txPowerA", ra.getTxPower());
                change.put("txPowerB", rb.getTxPower());
                change.put("txDelta", Math.round(txDelta * 10.0) / 10.0);
                change.put("rxPowerA", ra.getRxPower());
                change.put("rxPowerB", rb.getRxPower());
                change.put("rxDelta", Math.round(rxDelta * 10.0) / 10.0);
                change.put("statusA", getPortStatusText(ra));
                change.put("statusB", getPortStatusText(rb));
                changes.add(change);
            }
        }

        changes.sort((a, b) -> {
            boolean aDegrade = "status_change".equals(a.get("type"));
            boolean bDegrade = "status_change".equals(b.get("type"));
            if (aDegrade != bDegrade) return aDegrade ? -1 : 1;
            double aDelta = Math.abs((double) a.getOrDefault("rxDelta", 0.0));
            double bDelta = Math.abs((double) b.getOrDefault("rxDelta", 0.0));
            return Double.compare(bDelta, aDelta);
        });

        result.put("roundA", roundA);
        result.put("roundB", roundB);
        result.put("totalA", dataA.size());
        result.put("totalB", dataB.size());
        result.put("changes", changes);
        return result;
    }

    private double safeDelta(Double b, Double a) {
        if (b == null || a == null) return 0;
        return b - a;
    }

    private String getPortStatusText(OpticalPowerInspection r) {
        if (!Boolean.TRUE.equals(r.getSupported())) return "无效";
        if ((r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0)
                || (r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0)) {
            boolean over = (r.getTxPowerStatus() != null && r.getTxPowerStatus() == 2)
                    || (r.getRxPowerStatus() != null && r.getRxPowerStatus() == 2);
            return over ? "过载" : "劣化";
        }
        return "正常";
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
     * 获取最新轮次中去重的端口列表（neId + portName）
     */
    public List<Map<String, String>> getPortNames() {
        List<Object[]> rows = powerRecordRepository.findDistinctPortsInLatestRound();
        List<Map<String, String>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("neId", (String) row[0]);
            m.put("portName", (String) row[1]);
            result.add(m);
        }
        return result;
    }

    /**
     * 多轮次趋势数据：按端口分组，每个端口包含各轮次的功率值
     */
    public Map<String, Object> getTrendData(List<Long> roundIds, String network, String neId, String portName) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 查询轮次元数据并按时间排序
        List<InspectionRound> rounds = inspectionRoundRepository.findAllById(roundIds);
        rounds.sort(Comparator.comparing(r -> r.getStartTime() != null ? r.getStartTime() : LocalDateTime.MIN));

        // 轮次时间轴
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (InspectionRound r : rounds) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("roundId", r.getId());
            t.put("time", r.getStartTime());
            timeline.add(t);
        }
        result.put("timeline", timeline);

        // 批量查询所有轮次数据，避免 N+1
        List<Long> validRoundIds = rounds.stream().map(InspectionRound::getId).toList();
        List<OpticalPowerInspection> allRecords = thresholdService.applyThresholds(
                powerRecordRepository.findByRoundIdIn(validRoundIds));
        // 按 roundId 分组
        Map<Long, List<OpticalPowerInspection>> recordsByRound = allRecords.stream()
                .collect(java.util.stream.Collectors.groupingBy(OpticalPowerInspection::getRoundId));

        // 收集所有端口数据：key = neId:slotNo:portNo
        Map<String, Map<String, Object>> portMap = new LinkedHashMap<>();

        for (InspectionRound round : rounds) {
            List<OpticalPowerInspection> records = recordsByRound.getOrDefault(round.getId(), List.of());

            for (OpticalPowerInspection r : records) {
                // 筛选
                if (network != null && !network.isEmpty() && !network.equals(r.getNetworkName())) continue;
                if (neId != null && !neId.isEmpty() && !neId.equals(r.getNeId())) continue;
                if (portName != null && !portName.isEmpty() && !portName.equals(r.getPortName())) continue;

                String key = r.getNeId() + ":" + r.getSlotNo() + ":" + r.getPortNo();
                portMap.computeIfAbsent(key, k -> {
                    Map<String, Object> port = new LinkedHashMap<>();
                    port.put("neId", r.getNeId());
                    port.put("neName", r.getNeName());
                    port.put("slotNo", r.getSlotNo());
                    port.put("portNo", r.getPortNo());
                    port.put("portName", r.getPortName());
                    port.put("moduleTypeKey", r.getModuleTypeKey());
                    port.put("rounds", new LinkedHashMap<Long, Object>());
                    return port;
                });

                Map<String, Object> port = portMap.get(key);
                @SuppressWarnings("unchecked")
                Map<Long, Object> roundsData = (Map<Long, Object>) port.get("rounds");

                Map<String, Object> rd = new LinkedHashMap<>();
                rd.put("txPower", r.getTxPower());
                rd.put("rxPower", r.getRxPower());
                rd.put("txStatus", r.getTxPowerStatus());
                rd.put("rxStatus", r.getRxPowerStatus());
                rd.put("rxLow", r.getLowThreshold());
                rd.put("rxHigh", r.getHighThreshold());
                rd.put("txLow", r.getTxLowThreshold());
                rd.put("txHigh", r.getTxHighThreshold());
                roundsData.put(round.getId(), rd);
            }
        }

        // 将 rounds map 转为有序列表
        List<Map<String, Object>> ports = new ArrayList<>();
        for (Map<String, Object> port : portMap.values()) {
            @SuppressWarnings("unchecked")
            Map<Long, Object> roundsData = (Map<Long, Object>) port.get("rounds");
            List<Map<String, Object>> roundList = new ArrayList<>();
            for (InspectionRound round : rounds) {
                Object rd = roundsData.get(round.getId());
                if (rd != null) {
                    roundList.add((Map<String, Object>) rd);
                } else {
                    roundList.add(null);
                }
            }
            port.put("roundData", roundList);
            port.remove("rounds");
            ports.add(port);
        }

        // 按网元名+槽位+端口号排序（使用 Number 拆箱避免 ClassCastException）
        ports.sort((a, b) -> {
            String na = (String) a.get("neName");
            String nb = (String) b.get("neName");
            int cmp = (na != null ? na : "").compareTo(nb != null ? nb : "");
            if (cmp != 0) return cmp;
            int sa = a.get("slotNo") != null ? ((Number) a.get("slotNo")).intValue() : 0;
            int sb = b.get("slotNo") != null ? ((Number) b.get("slotNo")).intValue() : 0;
            if (sa != sb) return sa - sb;
            int pa = a.get("portNo") != null ? ((Number) a.get("portNo")).intValue() : 0;
            int pb = b.get("portNo") != null ? ((Number) b.get("portNo")).intValue() : 0;
            return pa - pb;
        });

        result.put("ports", ports);
        return result;
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

        // 异常端口Top10
        List<Map<String, Object>> topAnomalies = supported.stream()
                .filter(r -> (r.getTxPowerStatus() != null && r.getTxPowerStatus() > 0)
                        || (r.getRxPowerStatus() != null && r.getRxPowerStatus() > 0))
                .sorted((a, b) -> {
                    int sa = Math.max(a.getTxPowerStatus() != null ? a.getTxPowerStatus() : 0,
                            a.getRxPowerStatus() != null ? a.getRxPowerStatus() : 0);
                    int sb = Math.max(b.getTxPowerStatus() != null ? b.getTxPowerStatus() : 0,
                            b.getRxPowerStatus() != null ? b.getRxPowerStatus() : 0);
                    return sb - sa;
                })
                .limit(10)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("neName", r.getNeName());
                    m.put("slotNo", r.getSlotNo());
                    m.put("portNo", r.getPortNo());
                    m.put("portName", r.getPortName());
                    m.put("txPower", r.getTxPower());
                    m.put("rxPower", r.getRxPower());
                    m.put("txStatus", r.getTxPowerStatus());
                    m.put("rxStatus", r.getRxPowerStatus());
                    return m;
                }).toList();
        summary.put("topAnomalies", topAnomalies);

        // 耗时（秒）
        if (latest.getStartTime() != null && latest.getEndTime() != null) {
            long durSec = java.time.Duration.between(latest.getStartTime(), latest.getEndTime()).getSeconds();
            summary.put("durationSec", durSec);
        }

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
        progressCurrentPort = "";

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
                        Map<String, String> failInfo = new LinkedHashMap<>();
                        failInfo.put("device", device.getNeName() + "(" + device.getIpAddr() + ")");
                        failInfo.put("reason", e.getMessage());
                        failInfo.put("time", java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now()));
                        progressFailures.add(failInfo);
                    } finally {
                        progressCurrent.incrementAndGet();
                    }
                }, pool);
                futures.add(future);
            }

            // 等待全部完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 巡检完成后自动断开连接（在保存轮次状态之前，确保进度查询不会提前返回COMPLETED）
        if (autoDisconnect) {
            int disconnected = 0;
            for (DeviceAccessConfig device : targets) {
                if (qxConnectionService.isConnected(device.getNeId())) {
                    try {
                        qxConnectionService.disconnectSingle(device.getNeId());
                        disconnected++;
                    } catch (Exception e) {
                        log.debug("断开连接失败: {}", device.getNeName());
                    }
                }
            }
            log.info("巡检完成，已断开 {} 台设备连接", disconnected);
        }

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
        List<OpticalPowerInspection> records = new ArrayList<>();

        // 确保设备已连接（未连接则先建立连接）
        if (!qxConnectionService.isConnected(device.getNeId())) {
            if (!autoConnect) {
                log.debug("设备未连接且自动连接已关闭，跳过: {}", device.getNeName());
                records.add(buildDeviceFailRecord(round, device, "设备未连接"));
                powerRecordRepository.saveAll(records);
                return;
            }
            log.debug("设备未连接，尝试建立连接: {}", device.getNeName());
            Map<String, Object> connResult = qxConnectionService.connectSingle(device.getNeId());
            if (!Boolean.TRUE.equals(connResult.get("success"))) {
                String reason = (String) connResult.getOrDefault("message", "设备连接失败");
                records.add(buildDeviceFailRecord(round, device, reason));
                powerRecordRepository.saveAll(records);
                throw new RuntimeException(reason);
            }
        }

        // 从 dmeo 表查询该网元下的光口端口（替代 0x2406）
        List<Map<String, Object>> opticalPorts = queryOpticalPorts(device.getNeId());
        if (opticalPorts.isEmpty()) {
            log.debug("设备无光口端口: {}", device.getNeName());
            return;
        }

        String neId = device.getNeId();
        int failPorts = 0;

        for (Map<String, Object> dmeoPort : opticalPorts) {
            String oid = (String) dmeoPort.get("oid");
            int subrackId = OidUtil.getSubrackId(oid);
            int slotId = OidUtil.getSlotId(oid);
            int portId = OidUtil.getPortId(oid);
            int portType = dmeoPort.get("type") != null ? ((Number) dmeoPort.get("type")).intValue() : 0xFF;
            String portName = (String) dmeoPort.get("name");
            progressCurrentPort = "槽位" + slotId + " 端口" + portId + (portName != null ? " (" + portName + ")" : "");

            try {
                LaserAttributeGetData req = LaserAttributeGetData.builder()
                        .subcaseNo(subrackId)
                        .slotId(slotId)
                        .portType(portType)
                        .portSubType(0xFF)
                        .portId(portId)
                        .backup(0)
                        .build();

                LaserAttributeAckData laser = laserService.attributeGet(neId, req);
                records.add(buildRecord(round, device, slotId, portType, 0xFF, portId, (String) dmeoPort.get("name"), laser));
            } catch (Exception e) {
                failPorts++;
                records.add(buildFailRecord(round, device, slotId, portType, 0xFF, portId, (String) dmeoPort.get("name"), e.getMessage()));
                log.debug("端口查询失败: {} oid={}, {}",
                        device.getNeName(), oid, e.getMessage());
            }
        }
        progressCurrentPort = "";

        // 批量保存（可选过滤无效记录）
        if (!records.isEmpty()) {
            List<OpticalPowerInspection> toSave = saveInvalid ? records : records.stream()
                    .filter(r -> Boolean.TRUE.equals(r.getSupported()))
                    .toList();
            if (!toSave.isEmpty()) {
                powerRecordRepository.saveAll(toSave);
            }
        }

        log.debug("设备巡检完成: {}, 光口数={}, 失败={}", device.getNeName(), opticalPorts.size(), failPorts);
    }

    /**
     * 从 SQLite dmeo 表查询该网元下符合 defName 模式的光口端口
     */
    private List<Map<String, Object>> queryOpticalPorts(String neId) {
        String[] patterns = parsePatterns();
        // 构建 defName LIKE 条件（OR 逻辑）
        StringBuilder where = new StringBuilder("cid = 5 AND oid LIKE ? || ':%' AND (");
        List<Object> params = new ArrayList<>();
        params.add(neId);

        List<String> likeConditions = new ArrayList<>();
        for (String p : patterns) {
            if (p != null) {
                likeConditions.add("defName LIKE ?");
                params.add(p);
            }
        }
        if (likeConditions.isEmpty()) {
            return List.of();
        }
        where.append(String.join(" OR ", likeConditions));
        where.append(")");

        return sqliteJdbc.queryForList(
                "SELECT * FROM \"dmeo\" WHERE " + where, params.toArray());
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

    private OpticalPowerInspection buildRecord(InspectionRound round, DeviceAccessConfig device,
                                                int slotId, int portType, int portSubType, int portId,
                                                String portName, LaserAttributeAckData laser) {
        OpticalPowerInspection r = new OpticalPowerInspection();
        r.setRoundId(round.getId());
        r.setNeId(device.getNeId());
        r.setNeName(device.getNeName());
        r.setNetworkName(device.getNetworkName());
        r.setNeTypeName(device.getNeTypeName());
        r.setSlotNo(slotId);
        r.setPortNo(portId);
        r.setPortName(portName);
        r.setPortType(portType);
        r.setPortSubType(portSubType);
        r.setInspectionTime(LocalDateTime.now());

        if (laser == null) {
            r.setSupported(false);
            r.setFailReason("激光器查询无响应");
            return r;
        }

        boolean supported = (laser.getSupportFlag() & 0x01) == 1;
        r.setSupported(supported);
        if (!supported) {
            return r;
        }

        r.setLaserType(toLaserTypeName(laser.getLaserType()));
        r.setLaserDistance(toDistanceName(laser.getLaserType(), laser.getDistance()));
        r.setModuleTypeKey(toModuleTypeName(laser.getLaserType(), laser.getDistance()));
        r.setPartNumber(laser.getPartNumber());
        r.setVendorName(laser.getVendorName());
        r.setLaserWave(toLaserWaveName(laser.getLaserWave()));
        r.setLaserState(laser.getLaserState());
        r.setTxPower(toOpticalPower(laser.getTranLaserPower(), laser.getLaserState()));
        r.setRxPower(toOpticalPower(laser.getRecvLaserPower(), laser.getLaserState()));

        return r;
    }

    private OpticalPowerInspection buildFailRecord(InspectionRound round, DeviceAccessConfig device,
                                                    int slotId, int portType, int portSubType, int portId,
                                                    String portName, String reason) {
        OpticalPowerInspection r = new OpticalPowerInspection();
        r.setRoundId(round.getId());
        r.setNeId(device.getNeId());
        r.setNeName(device.getNeName());
        r.setNetworkName(device.getNetworkName());
        r.setNeTypeName(device.getNeTypeName());
        r.setSlotNo(slotId);
        r.setPortNo(portId);
        r.setPortName(portName);
        r.setPortType(portType);
        r.setPortSubType(portSubType);
        r.setSupported(false);
        r.setFailReason(reason);
        r.setInspectionTime(LocalDateTime.now());
        return r;
    }

    private OpticalPowerInspection buildDeviceFailRecord(InspectionRound round, DeviceAccessConfig device,
                                                          String reason) {
        OpticalPowerInspection r = new OpticalPowerInspection();
        r.setRoundId(round.getId());
        r.setNeId(device.getNeId());
        r.setNeName(device.getNeName());
        r.setNetworkName(device.getNetworkName());
        r.setNeTypeName(device.getNeTypeName());
        r.setSupported(false);
        r.setFailReason(reason);
        r.setInspectionTime(LocalDateTime.now());
        return r;
    }

    /**
     * 将速率+距离档组合为标准光模块型号名（与老网管一致）
     * 例: 2.5G+L档 → "L16.1", 155M+I档 → "I1.1", GE+SX → "1000BASE-SX"
     */
    private static String toModuleTypeName(int laserType, int distance) {
        if (laserType == 0x10) {
            return switch (distance) {
                case 0x10 -> "1000BASE-SX";
                case 0x11 -> "1000BASE-LX";
                default -> "GE-Unknown(" + distance + ")";
            };
        }
        // STM 速率代号: 2.5G→16, 622M→4, 155M→1, 10G→64
        String speedCode = switch (laserType) {
            case 1 -> "16";   // 2.5G = STM-16
            case 2 -> "4";    // 622M = STM-4
            case 3 -> "1";    // 155M = STM-1
            case 4 -> "64";   // 10G = STM-64 (850nm)
            default -> "?";
        };
        // 距离档模板: 850nm 用 .2/.2b 后缀，其他用 .1
        String template = switch (distance) {
            case 1 -> "I{0}.1";
            case 2 -> (laserType == 4) ? "S{0}.2b" : "S{0}.1";
            case 3 -> (laserType == 4) ? "L{0}.2" : "L{0}.1";
            case 4 -> (laserType == 4) ? "V{0}.2" : "L{0}.2";
            default -> "Unknown(" + distance + ")";
        };
        return java.text.MessageFormat.format(template, speedCode);
    }

    private static String toLaserTypeName(int laserType) {
        return switch (laserType) {
            case 1 -> "2.5G";
            case 2 -> "622M";
            case 3 -> "155M";
            case 4 -> "10G";
            case 0x10 -> "GE";
            default -> "Unknown(" + laserType + ")";
        };
    }

    private static String toDistanceName(int laserType, int distance) {
        if (laserType == 0x10) {
            return switch (distance) {
                case 0x10 -> "SX";
                case 0x11 -> "LX";
                default -> "Unknown(" + distance + ")";
            };
        }
        return switch (distance) {
            case 1 -> "I";
            case 2 -> "S";
            case 3 -> "L";
            case 4 -> "V";
            default -> "Unknown(" + distance + ")";
        };
    }

    private static String toLaserWaveName(int laserWave) {
        return switch (laserWave) {
            case 1 -> "1310nm";
            case 2 -> "1550nm";
            case 3 -> "850nm";
            default -> "Unknown(" + laserWave + ")";
        };
    }

    /**
     * 将原始整数光功率转换为 dBm 值。
     * 设备返回 0xFFFFFFFF 表示无光功率读数。
     */
    private static Double toOpticalPower(int rawPower, int laserState) {
        if (rawPower == 0xFFFFFFFF) {
            return null; // 无光功率
        }
        return (double) Float.intBitsToFloat(rawPower);
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

    /**
     * 获取采集参数
     */
    public Map<String, Object> getCollectParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("concurrency", concurrency);
        params.put("maxRounds", maxRounds);
        params.put("autoConnect", autoConnect);
        params.put("autoDisconnect", autoDisconnect);
        params.put("saveInvalid", saveInvalid);
        return params;
    }

    /**
     * 更新采集参数
     */
    public void updateCollectParams(int concurrency, int maxRounds, boolean autoConnect, boolean autoDisconnect, boolean saveInvalid) {
        this.concurrency = Math.max(1, Math.min(50, concurrency));
        this.maxRounds = Math.max(5, Math.min(200, maxRounds));
        this.autoConnect = autoConnect;
        this.autoDisconnect = autoDisconnect;
        this.saveInvalid = saveInvalid;
        try {
            sysConfigService.set(KEY_CONCURRENCY, String.valueOf(this.concurrency));
            sysConfigService.set(KEY_MAX_ROUNDS, String.valueOf(this.maxRounds));
            sysConfigService.set(KEY_AUTO_CONNECT, String.valueOf(autoConnect));
            sysConfigService.set(KEY_AUTO_DISCONNECT, String.valueOf(autoDisconnect));
            sysConfigService.set(KEY_SAVE_INVALID, String.valueOf(saveInvalid));
        } catch (Exception e) {
            log.error("采集参数持久化失败: {}", e.getMessage(), e);
        }
        log.info("采集参数已更新: concurrency={}, maxRounds={}, autoConnect={}, autoDisconnect={}, saveInvalid={}",
                concurrency, maxRounds, autoConnect, autoDisconnect, saveInvalid);
    }
}
