package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.entity.sqlite.LinkInspectionResult;
import com.optel.qxinspection.laser.LaserAttributeAckData;
import com.optel.qxinspection.laser.LaserAttributeGetData;
import com.optel.qxinspection.laser.service.ILaserService;
import com.optel.qxinspection.repository.sqlite.InspectionRoundRepository;
import com.optel.qxinspection.repository.sqlite.LinkInspectionResultRepository;
import com.optel.qxinspection.util.OidUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链路巡检服务。
 * <p>巡检对象为 dmconnection 中的物理链路：按网元分组，每台网元连接一次，
 * 采集链路两端端口的光功率与带宽，按链路组装结果写入 link_inspection_result。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private static final String KEY_CONCURRENCY = "collect.concurrency";
    private static final String KEY_MAX_ROUNDS = "collect.maxRounds";
    private static final String KEY_AUTO_CONNECT = "inspect.autoConnect";
    private static final String KEY_AUTO_DISCONNECT = "inspect.autoDisconnect";
    private static final String KEY_SAVE_INVALID = "inspect.saveInvalid";

    private static final int DEFAULT_PORT_TYPE = 0xFF;
    private static final int DEFAULT_PORT_SUB_TYPE = 0xFF;
    private static final int LASER_SUPPORT_BIT = 0x01;
    private static final int TOP_ANOMALY_LIMIT = 20;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每个 VC-12 折算的 Mbps 数：STM-1 = 155.52 Mbps 含 63 个 VC-12 */
    private static final double MBPS_PER_VC12 = 155.52 / 63;
    private static final DateTimeFormatter FAILURE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 链路基础信息（dmconnection cid=100，冗余字段由同步时写入） */
    private static final String LINK_SELECT_SQL = """
            SELECT "oid", "name", "aEnd", "zEnd",
                   "aNetworkName", "aNeName", "aNeTypeName", "aPortName", "aCapacity", "aUsed",
                   "zNetworkName", "zNeName", "zNeTypeName", "zPortName", "zCapacity", "zUsed"
            FROM "dmconnection" WHERE "cid" = 100 ORDER BY "name"
            """;

    private final ILaserService laserService;
    private final QxConnectionService qxConnectionService;
    private final InspectionRoundRepository inspectionRoundRepository;
    private final LinkInspectionResultRepository linkResultRepository;
    private final JdbcTemplate sqliteJdbc;
    private final ThresholdService thresholdService;
    private final SysConfigService sysConfigService;

    @Value("${app.inspection.concurrency:10}")
    private int concurrency;

    @Value("${app.inspection.max-rounds:10}")
    private int maxRounds;

    /** 是否巡检时自动连接未在线设备 */
    private volatile boolean autoConnect = true;
    /** 是否巡检完成后自动断开所有连接 */
    private volatile boolean autoDisconnect = true;
    /** 是否保存未采集到任何数据的链路 */
    private volatile boolean saveInvalid = true;

    /** 当前运行中的轮次（用于进度查询） */
    private volatile InspectionRound currentRound;
    private final AtomicInteger progressCurrent = new AtomicInteger(0);
    private final List<Map<String, String>> progressFailures = new CopyOnWriteArrayList<>();
    /** 本轮巡检涉及的 OID → 名称，进度条上显示名称而不是 OID（触发巡检时重建） */
    private final Map<String, String> roundNeNames = new ConcurrentHashMap<>();
    private final Map<String, String> roundPortNames = new ConcurrentHashMap<>();
    private volatile String progressCurrentNe = "";
    private volatile String progressCurrentPort = "";

    @jakarta.annotation.PostConstruct
    public void init() {
        thresholdService.initPresetThresholds();
        // 从数据库加载采集参数（覆盖@Value默认值）
        concurrency = Integer.parseInt(sysConfigService.get(KEY_CONCURRENCY, String.valueOf(concurrency)));
        maxRounds = Integer.parseInt(sysConfigService.get(KEY_MAX_ROUNDS, String.valueOf(maxRounds)));
        autoConnect = Boolean.parseBoolean(sysConfigService.get(KEY_AUTO_CONNECT, "true"));
        autoDisconnect = Boolean.parseBoolean(sysConfigService.get(KEY_AUTO_DISCONNECT, "true"));
        saveInvalid = Boolean.parseBoolean(sysConfigService.get(KEY_SAVE_INVALID, "true"));
        log.info("采集参数加载: concurrency={}, maxRounds={}, autoConnect={}, autoDisconnect={}, saveInvalid={}",
                concurrency, maxRounds, autoConnect, autoDisconnect, saveInvalid);
    }

    // ========== 触发巡检 ==========

    /** 手动触发巡检（全网链路） */
    public InspectionRound triggerInspectionAll() {
        return triggerInspection("ALL", null, "MANUAL");
    }

    /** 按网络触发巡检 */
    public InspectionRound triggerInspectionByNetwork(String networkName) {
        return triggerInspection("NETWORK", networkName, "MANUAL");
    }

    /** 按单个网元触发巡检 */
    public InspectionRound triggerInspectionByNe(String neId) {
        return triggerInspection("SINGLE", neId, "MANUAL");
    }

    /** 定时触发巡检（内部用） */
    public InspectionRound triggerScheduledInspection(String scopeType, String scopeParam) {
        return triggerInspection(scopeType, scopeParam, "SCHEDULED");
    }

    /** 获取当前巡检进度（按链路数统计） */
    public synchronized Map<String, Object> getProgress() {
        Map<String, Object> progress = new LinkedHashMap<>();
        InspectionRound round = currentRound;
        if (round == null) {
            progress.put("running", false);
            return progress;
        }
        progress.put("running", InspectionRound.STATUS_RUNNING.equals(round.getStatus()));
        progress.put("roundId", round.getId());
        progress.put("total", round.getTotalCount());
        progress.put("done", progressCurrent.get());
        progress.put("failures", progressFailures.size());
        progress.put("failures_list", new ArrayList<>(progressFailures));
        progress.put("currentNe", progressCurrentNe);
        progress.put("currentPort", progressCurrentPort);
        return progress;
    }

    /** 获取巡检轮次列表 */
    public List<InspectionRound> listRounds() {
        return inspectionRoundRepository.findAll();
    }

    // ========== 链路巡检结果查询 ==========

    /**
     * 获取链路巡检结果（A端+Z端合并显示）
     */
    public List<LinkInspectionResult> getLinkResults(Long roundId, String network) {
        List<LinkInspectionResult> results;
        if (roundId != null) {
            results = linkResultRepository.findByRoundId(roundId);
        } else {
            results = inspectionRoundRepository.findFirstByOrderByStartTimeDesc()
                    .map(r -> linkResultRepository.findByRoundId(r.getId()))
                    .orElseGet(Collections::emptyList);
        }

        if (network != null && !network.isEmpty()) {
            Map<String, String> neNetworkMap = loadNeNetworkMap();
            results = results.stream()
                    .filter(r -> network.equals(neNetworkMap.get(r.getANeId()))
                            || network.equals(neNetworkMap.get(r.getZNeId())))
                    .toList();
        }

        int seq = 1;
        for (LinkInspectionResult result : results) {
            result.setSeqNo(seq++);
            // 库中存 VC-12 个数，对外统一按 Mbps 呈现。本方法是接口与 Excel 导出的唯一出口。
            // 这里实体是 detached（方法无 @Transactional），原地换算不会被回写；
            // 若将来给本方法加 @Transactional 或从事务内调用，会把 Mbps 写回库，必须改走 DTO。
            result.setATotalBandwidth(vc12ToMbps(result.getATotalBandwidth()));
            result.setAUsedBandwidth(vc12ToMbps(result.getAUsedBandwidth()));
            result.setZTotalBandwidth(vc12ToMbps(result.getZTotalBandwidth()));
            result.setZUsedBandwidth(vc12ToMbps(result.getZUsedBandwidth()));
        }
        return results;
    }

    /** 网元 OID → 所属网络名称 */
    private Map<String, String> loadNeNetworkMap() {
        Map<String, String> map = new HashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList(
                "SELECT \"oid\", \"networkName\" FROM \"dmeo\" WHERE \"cid\" = 2 AND \"networkName\" IS NOT NULL")) {
            map.put((String) row.get("oid"), (String) row.get("networkName"));
        }
        return map;
    }

    /**
     * 获取巡检摘要统计（链路口径）
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
        summary.put("totalLinks", latest.getTotalCount());
        summary.put("doneLinks", latest.getDoneCount());
        summary.put("failNes", latest.getFailCount());

        List<LinkInspectionResult> links = linkResultRepository.findByRoundId(latest.getId());
        long abnormal = links.stream().filter(this::isAbnormal).count();
        long noLight = links.stream().filter(this::hasNoLight).count();
        summary.put("linkCount", links.size());
        summary.put("normalLinks", links.size() - abnormal);
        summary.put("abnormalLinks", abnormal);
        summary.put("noLightLinks", noLight);
        summary.put("byModuleType", groupByModuleType(links));
        summary.put("byNeType", groupByNeType(links));
        summary.put("topAnomalies", topAnomalies(links));

        if (latest.getStartTime() != null && latest.getEndTime() != null) {
            summary.put("durationSec", Duration.between(latest.getStartTime(), latest.getEndTime()).getSeconds());
        }
        return summary;
    }

    /** 按光模块类型统计：count=采集到的端数，abnormal=其中异常端数 */
    private Map<String, Map<String, Object>> groupByModuleType(List<LinkInspectionResult> links) {
        Map<String, long[]> counters = new LinkedHashMap<>();
        for (LinkInspectionResult r : links) {
            countModuleType(counters, r.getAModuleType(), sideAbnormal(r.getATxStatus(), r.getARxStatus(), r.getAErrorInfo()));
            countModuleType(counters, r.getZModuleType(), sideAbnormal(r.getZTxStatus(), r.getZRxStatus(), r.getZErrorInfo()));
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        counters.forEach((key, value) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", value[0]);
            item.put("abnormal", value[1]);
            result.put(key, item);
        });
        return result;
    }

    private static void countModuleType(Map<String, long[]> counters, String moduleType, boolean abnormal) {
        if (moduleType == null || moduleType.isEmpty()) {
            return;
        }
        long[] counter = counters.computeIfAbsent(moduleType, k -> new long[2]);
        counter[0]++;
        if (abnormal) {
            counter[1]++;
        }
    }

    /** 按网元类型统计：count=涉及链路数，abnormal=其中异常链路数 */
    private Map<String, Map<String, Object>> groupByNeType(List<LinkInspectionResult> links) {
        Map<String, long[]> counters = new LinkedHashMap<>();
        for (LinkInspectionResult r : links) {
            boolean abnormal = isAbnormal(r);
            countNeType(counters, r.getANeTypeName(), abnormal);
            if (!Objects.equals(r.getANeId(), r.getZNeId())) {
                countNeType(counters, r.getZNeTypeName(), abnormal);
            }
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        counters.forEach((key, value) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", value[0]);
            item.put("abnormal", value[1]);
            result.put(key, item);
        });
        return result;
    }

    private static void countNeType(Map<String, long[]> counters, String neTypeName, boolean abnormal) {
        if (neTypeName == null || neTypeName.isEmpty()) {
            return;
        }
        long[] counter = counters.computeIfAbsent(neTypeName, k -> new long[2]);
        counter[0]++;
        if (abnormal) {
            counter[1]++;
        }
    }

    /** 异常明细（按严重程度排序，最多 TOP_ANOMALY_LIMIT 条） */
    private List<Map<String, Object>> topAnomalies(List<LinkInspectionResult> links) {
        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (LinkInspectionResult r : links) {
            collectAnomalies(r, true, anomalies);
            collectAnomalies(r, false, anomalies);
        }
        anomalies.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("severity")).reversed());
        return anomalies.size() > TOP_ANOMALY_LIMIT ? anomalies.subList(0, TOP_ANOMALY_LIMIT) : anomalies;
    }

    private void collectAnomalies(LinkInspectionResult r, boolean aEnd, List<Map<String, Object>> sink) {
        String txStatus = aEnd ? r.getATxStatus() : r.getZTxStatus();
        String rxStatus = aEnd ? r.getARxStatus() : r.getZRxStatus();
        String errorInfo = aEnd ? r.getAErrorInfo() : r.getZErrorInfo();
        if (!sideAbnormal(txStatus, rxStatus, errorInfo)) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("side", aEnd ? "A" : "Z");
        item.put("linkName", r.getLinkName());
        item.put("neName", aEnd ? r.getANeName() : r.getZNeName());
        item.put("portName", aEnd ? r.getAPortName() : r.getZPortName());
        item.put("moduleType", aEnd ? r.getAModuleType() : r.getZModuleType());
        item.put("txPower", aEnd ? r.getATxPower() : r.getZTxPower());
        item.put("rxPower", aEnd ? r.getARxPower() : r.getZRxPower());
        item.put("txStatus", txStatus);
        item.put("rxStatus", rxStatus);
        item.put("errorInfo", errorInfo);
        item.put("severity", Math.max(severity(txStatus), severity(rxStatus)));
        sink.add(item);
    }

    private static int severity(String status) {
        if (ThresholdService.STATUS_NO_LIGHT.equals(status)) return 3;
        if (ThresholdService.STATUS_HIGH.equals(status)) return 2;
        if (ThresholdService.STATUS_LOW.equals(status)) return 1;
        return 0;
    }

    private boolean isAbnormal(LinkInspectionResult r) {
        return sideAbnormal(r.getATxStatus(), r.getARxStatus(), r.getAErrorInfo())
                || sideAbnormal(r.getZTxStatus(), r.getZRxStatus(), r.getZErrorInfo());
    }

    private boolean hasNoLight(LinkInspectionResult r) {
        return ThresholdService.STATUS_NO_LIGHT.equals(r.getATxStatus())
                || ThresholdService.STATUS_NO_LIGHT.equals(r.getARxStatus())
                || ThresholdService.STATUS_NO_LIGHT.equals(r.getZTxStatus())
                || ThresholdService.STATUS_NO_LIGHT.equals(r.getZRxStatus());
    }

    /** 采集失败，或门限判定为过高/过低/无光，均视为异常 */
    private static boolean sideAbnormal(String txStatus, String rxStatus, String errorInfo) {
        if (errorInfo != null && !errorInfo.isEmpty()) {
            return true;
        }
        return isBadStatus(txStatus) || isBadStatus(rxStatus);
    }

    private static boolean isBadStatus(String status) {
        return ThresholdService.STATUS_HIGH.equals(status)
                || ThresholdService.STATUS_LOW.equals(status)
                || ThresholdService.STATUS_NO_LIGHT.equals(status);
    }

    // ========== 采集参数 ==========

    public Map<String, Object> getCollectParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("concurrency", concurrency);
        params.put("maxRounds", maxRounds);
        params.put("autoConnect", autoConnect);
        params.put("autoDisconnect", autoDisconnect);
        params.put("saveInvalid", saveInvalid);
        return params;
    }

    public void updateCollectParams(int concurrency, int maxRounds, boolean autoConnect,
                                    boolean autoDisconnect, boolean saveInvalid) {
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

    // ========== 内部实现 ==========

    private synchronized InspectionRound triggerInspection(String scopeType, String scopeParam, String triggerType) {
        if (currentRound != null && InspectionRound.STATUS_RUNNING.equals(currentRound.getStatus())) {
            throw new IllegalStateException("已有巡检任务正在运行，请等待完成后再触发");
        }

        List<Map<String, Object>> links = loadLinks(scopeType, scopeParam);
        if (links.isEmpty()) {
            throw new IllegalStateException("未找到可巡检的链路，请先在「数据维护」页面同步业务数据");
        }

        InspectionRound round = new InspectionRound();
        round.setTriggerType(triggerType);
        round.setScopeType(scopeType);
        round.setScopeParam(scopeParam);
        round.setTotalCount(links.size());
        InspectionRound saved = inspectionRoundRepository.save(round);

        currentRound = saved;
        progressCurrent.set(0);
        progressFailures.clear();
        roundNeNames.clear();
        roundPortNames.clear();
        progressCurrentNe = "";
        progressCurrentPort = "";

        ExecutorService inspectionPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "inspection-main");
            t.setDaemon(true);
            return t;
        });
        CompletableFuture.runAsync(() -> executeInspection(saved, links), inspectionPool)
                .exceptionally(ex -> {
                    log.error("巡检执行异常: roundId={}, {}", saved.getId(), ex.getMessage(), ex);
                    saved.setStatus(InspectionRound.STATUS_FAILED);
                    saved.setEndTime(LocalDateTime.now());
                    inspectionRoundRepository.save(saved);
                    return null;
                })
                .thenRun(inspectionPool::shutdown);

        return saved;
    }

    /**
     * 按范围加载链路：网络名匹配任一端，或网元匹配任一端。
     */
    private List<Map<String, Object>> loadLinks(String scopeType, String scopeParam) {
        List<Map<String, Object>> all = sqliteJdbc.queryForList(LINK_SELECT_SQL);
        if (scopeParam == null || scopeParam.isEmpty()) {
            return all;
        }
        return all.stream().filter(link -> matchesScope(link, scopeType, scopeParam)).toList();
    }

    private boolean matchesScope(Map<String, Object> link, String scopeType, String scopeParam) {
        return switch (scopeType) {
            case "NETWORK" -> scopeParam.equals(str(link, "aNetworkName")) || scopeParam.equals(str(link, "zNetworkName"));
            case "SINGLE" -> scopeParam.equals(neIdOf(str(link, "aEnd"))) || scopeParam.equals(neIdOf(str(link, "zEnd")));
            default -> true;
        };
    }

    private void executeInspection(InspectionRound round, List<Map<String, Object>> links) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            // 网元 → 需要采集的端口；链路 → 未完成端数（用于按链路统计进度）
            Map<String, Set<String>> nePorts = new LinkedHashMap<>();
            Map<String, List<String>> neLinks = new HashMap<>();
            Map<String, AtomicInteger> linkPending = new HashMap<>();
            for (Map<String, Object> link : links) {
                String linkOid = str(link, "oid");
                String aNeId = neIdOf(str(link, "aEnd"));
                String zNeId = neIdOf(str(link, "zEnd"));
                addPort(nePorts, aNeId, str(link, "aEnd"));
                addPort(nePorts, zNeId, str(link, "zEnd"));
                recordNames(link);
                Set<String> distinctNes = new LinkedHashSet<>();
                distinctNes.add(aNeId);
                distinctNes.add(zNeId);
                distinctNes.remove(null);
                linkPending.put(linkOid, new AtomicInteger(distinctNes.size()));
                for (String neId : distinctNes) {
                    neLinks.computeIfAbsent(neId, k -> new ArrayList<>()).add(linkOid);
                }
            }

            Map<String, Integer> portTypes = loadPortTypes();
            Map<String, LaserSample> samples = new ConcurrentHashMap<>();
            AtomicInteger failCount = new AtomicInteger();

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : nePorts.entrySet()) {
                String neId = entry.getKey();
                Set<String> ports = entry.getValue();
                futures.add(CompletableFuture.runAsync(
                        () -> collectNe(neId, ports, portTypes, samples, failCount, neLinks, linkPending), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<LinkInspectionResult> results = assembleLinks(round, links, samples);
            List<LinkInspectionResult> toSave = saveInvalid
                    ? results
                    : results.stream().filter(this::isCollected).toList();
            if (!toSave.isEmpty()) {
                linkResultRepository.saveAll(toSave);
            }

            if (autoDisconnect) {
                disconnectTargets(nePorts.keySet());
            }

            round.setDoneCount(toSave.size());
            round.setFailCount(failCount.get());
            round.setStatus(InspectionRound.STATUS_COMPLETED);
            round.setEndTime(LocalDateTime.now());
            inspectionRoundRepository.save(round);
            progressCurrentNe = "";
            progressCurrentPort = "";

            cleanupOldRounds();

            log.info("巡检完成: roundId={}, 链路={}, 保存={}, 网元失败={}",
                    round.getId(), links.size(), toSave.size(), failCount.get());
        } catch (Exception e) {
            round.setStatus(InspectionRound.STATUS_FAILED);
            round.setEndTime(LocalDateTime.now());
            inspectionRoundRepository.save(round);
            throw e;
        } finally {
            pool.shutdown();
        }
    }

    private static void addPort(Map<String, Set<String>> nePorts, String neId, String portOid) {
        if (neId == null || portOid == null) {
            return;
        }
        nePorts.computeIfAbsent(neId, k -> new LinkedHashSet<>()).add(portOid);
    }

    /** 记录链路两端的网元名/端口名：进度条显示名称，没采集成功也能看出正在处理哪台设备 */
    private void recordNames(Map<String, Object> link) {
        putName(roundNeNames, neIdOf(str(link, "aEnd")), str(link, "aNeName"));
        putName(roundNeNames, neIdOf(str(link, "zEnd")), str(link, "zNeName"));
        putName(roundPortNames, str(link, "aEnd"), str(link, "aPortName"));
        putName(roundPortNames, str(link, "zEnd"), str(link, "zPortName"));
    }

    private static void putName(Map<String, String> names, String oid, String name) {
        if (oid != null && name != null && !name.isEmpty()) {
            names.put(oid, name);
        }
    }

    /** 名称缺失时回退显示 OID，避免进度条出现空白 */
    private static String nameOr(Map<String, String> names, String oid) {
        String name = names.get(oid);
        return name != null ? name : oid;
    }

    /** 采集单台网元：连接一次，逐个端口采集光功率 */
    private void collectNe(String neId, Set<String> ports, Map<String, Integer> portTypes,
                           Map<String, LaserSample> samples, AtomicInteger failCount,
                           Map<String, List<String>> neLinks, Map<String, AtomicInteger> linkPending) {
        progressCurrentNe = nameOr(roundNeNames, neId);
        try {
            ensureConnected(neId);
            for (String portOid : ports) {
                progressCurrentPort = nameOr(roundPortNames, portOid);
                samples.put(portOid, collectPort(neId, portOid, portTypes));
            }
        } catch (Exception e) {
            failCount.incrementAndGet();
            Map<String, String> failInfo = new LinkedHashMap<>();
            failInfo.put("device", nameOr(roundNeNames, neId));
            failInfo.put("reason", e.getMessage());
            failInfo.put("time", LocalDateTime.now().format(FAILURE_TIME_FORMATTER));
            progressFailures.add(failInfo);
            log.error("网元采集失败: {}, {}", neId, e.getMessage());
            String reason = "网元采集失败: " + e.getMessage();
            for (String portOid : ports) {
                samples.putIfAbsent(portOid, LaserSample.error(reason));
            }
        } finally {
            markLinksDone(neLinks.get(neId), linkPending);
        }
    }

    /** 该网元采集结束后，把已完成的链路计入进度 */
    private void markLinksDone(List<String> linkOids, Map<String, AtomicInteger> linkPending) {
        if (linkOids == null) {
            return;
        }
        for (String linkOid : linkOids) {
            AtomicInteger pending = linkPending.get(linkOid);
            if (pending != null && pending.decrementAndGet() <= 0) {
                progressCurrent.incrementAndGet();
            }
        }
    }

    private void ensureConnected(String neId) {
        if (qxConnectionService.isConnected(neId)) {
            return;
        }
        if (!autoConnect) {
            throw new IllegalStateException("设备未连接");
        }
        Map<String, Object> connResult = qxConnectionService.connectSingle(neId);
        if (!Boolean.TRUE.equals(connResult.get("success"))) {
            throw new IllegalStateException(String.valueOf(connResult.getOrDefault("message", "设备连接失败")));
        }
    }

    /** 采集单个端口的光功率，失败只记录该端口的 error_info */
    private LaserSample collectPort(String neId, String portOid, Map<String, Integer> portTypes) {
        try {
            LaserAttributeGetData req = LaserAttributeGetData.builder()
                    .subcaseNo(OidUtil.getSubrackId(portOid))
                    .slotId(OidUtil.getSlotId(portOid))
                    .portType(portTypes.getOrDefault(portOid, DEFAULT_PORT_TYPE))
                    .portSubType(DEFAULT_PORT_SUB_TYPE)
                    .portId(OidUtil.getPortId(portOid))
                    .backup(0)
                    .build();
            return LaserSample.of(laserService.attributeGet(neId, req));
        } catch (Exception e) {
            log.debug("端口采集失败: ne={}, port={}, {}", neId, portOid, e.getMessage());
            return LaserSample.error("采集失败: " + e.getMessage());
        }
    }

    private void disconnectTargets(Set<String> neIds) {
        int disconnected = 0;
        for (String neId : neIds) {
            if (qxConnectionService.isConnected(neId)) {
                try {
                    qxConnectionService.disconnectSingle(neId);
                    disconnected++;
                } catch (Exception e) {
                    log.debug("断开连接失败: {}", neId);
                }
            }
        }
        log.info("巡检完成，已断开 {} 台设备连接", disconnected);
    }

    /** 端口 OID → dmeo.type（采集报文需要） */
    private Map<String, Integer> loadPortTypes() {
        Map<String, Integer> map = new HashMap<>();
        for (Map<String, Object> row : sqliteJdbc.queryForList(
                "SELECT \"oid\", \"type\" FROM \"dmeo\" WHERE \"cid\" = 5 AND \"type\" IS NOT NULL")) {
            Object type = row.get("type");
            if (type instanceof Number number) {
                map.put((String) row.get("oid"), number.intValue());
            }
        }
        return map;
    }

    private List<LinkInspectionResult> assembleLinks(InspectionRound round, List<Map<String, Object>> links,
                                                     Map<String, LaserSample> samples) {
        Map<String, ThresholdService.Range> ranges = thresholdService.loadRangeMap();
        String inspectionTime = LocalDateTime.now().format(TIME_FORMATTER);
        List<LinkInspectionResult> results = new ArrayList<>();
        int seq = 1;

        for (Map<String, Object> link : links) {
            LinkInspectionResult result = new LinkInspectionResult();
            result.setSeqNo(seq++);
            result.setRoundId(round.getId());
            result.setLinkOid(str(link, "oid"));
            result.setLinkName(str(link, "name"));
            result.setCreateTime(inspectionTime);

            SideData aSide = buildSide(endOf(link, true), samples, ranges);
            SideData zSide = buildSide(endOf(link, false), samples, ranges);
            if (aSide != null) {
                result.setANeId(aSide.neId());
                result.setANeName(aSide.neName());
                result.setANeTypeName(aSide.neTypeName());
                result.setAPortOid(aSide.portOid());
                result.setAPortName(aSide.portName());
                result.setAModuleType(aSide.moduleType());
                result.setATxPower(aSide.txPower());
                result.setARxPower(aSide.rxPower());
                result.setATxStatus(aSide.txStatus());
                result.setARxStatus(aSide.rxStatus());
                result.setARsErrorSec(aSide.rsErrorSec());
                result.setATotalBandwidth(doubleOrNull(aSide.totalBandwidth()));
                result.setAUsedBandwidth(doubleOrNull(aSide.usedBandwidth()));
                result.setABandwidthUsage(aSide.bandwidthUsage());
                result.setAErrorInfo(aSide.errorInfo());
            }
            if (zSide != null) {
                result.setZNeId(zSide.neId());
                result.setZNeName(zSide.neName());
                result.setZNeTypeName(zSide.neTypeName());
                result.setZPortOid(zSide.portOid());
                result.setZPortName(zSide.portName());
                result.setZModuleType(zSide.moduleType());
                result.setZTxPower(zSide.txPower());
                result.setZRxPower(zSide.rxPower());
                result.setZTxStatus(zSide.txStatus());
                result.setZRxStatus(zSide.rxStatus());
                result.setZRsErrorSec(zSide.rsErrorSec());
                result.setZTotalBandwidth(doubleOrNull(zSide.totalBandwidth()));
                result.setZUsedBandwidth(doubleOrNull(zSide.usedBandwidth()));
                result.setZBandwidthUsage(zSide.bandwidthUsage());
                result.setZErrorInfo(zSide.errorInfo());
            }
            results.add(result);
        }
        return results;
    }

    /** 读取链路某一端的静态信息（dmconnection 冗余字段） */
    private static LinkEnd endOf(Map<String, Object> link, boolean aEnd) {
        String prefix = aEnd ? "a" : "z";
        String portOid = str(link, prefix + "End");
        if (portOid == null) {
            return null;
        }
        return new LinkEnd(portOid, OidUtil.getNeOid(portOid),
                str(link, prefix + "NeName"), stripNeTypePrefix(str(link, prefix + "NeTypeName")),
                portNameOf(str(link, prefix + "PortName"), portOid),
                intOf(link.get(prefix + "Capacity")), intOf(link.get(prefix + "Used")));
    }

    /** 端口名称：同步已拼接好，缺失时回退为端口 OID */
    private static String portNameOf(String syncedPortName, String portOid) {
        return syncedPortName != null && !syncedPortName.isEmpty() ? syncedPortName : portOid;
    }

    private static SideData buildSide(LinkEnd end, Map<String, LaserSample> samples,
                                      Map<String, ThresholdService.Range> ranges) {
        if (end == null) {
            return null;
        }
        LaserSample sample = samples.get(end.portOid());
        String moduleType = sample != null ? sample.moduleType() : null;
        Double txPower = sample != null ? sample.txPower() : null;
        Double rxPower = sample != null ? sample.rxPower() : null;
        ThresholdService.Range range = ThresholdService.rangeFor(ranges, moduleType);

        return new SideData(end.neId(), end.neName(), end.neTypeName(), end.portOid(), end.portName(),
                moduleType, txPower, rxPower,
                ThresholdService.evaluateStatus(txPower, range.txLow(), range.txHigh()),
                ThresholdService.evaluateStatus(rxPower, range.rxLow(), range.rxHigh()),
                null, // RS错误秒：性能采集未接入，字段预留
                end.capacity(), end.used(), bandwidthUsage(end.used(), end.capacity()),
                sample != null ? sample.errorInfo() : "未采集到端口数据");
    }

    /** 带宽利用率（百分比，保留1位小数）。同单位比值，与存储单位无关 */
    private static Double bandwidthUsage(Integer used, Integer capacity) {
        if (used == null || capacity == null || capacity <= 0) {
            return null;
        }
        return Math.round(used * 1000.0 / capacity) / 10.0;
    }

    /**
     * VC-12 个数 → Mbps（保留 2 位小数），null 透传。
     * STM-1 = 155.52 Mbps 含 63 个 VC-12，故 63/252/1008/4032 对应 155.52/622.08/2488.32/9953.28 Mbps
     */
    private static Double vc12ToMbps(Double vc12) {
        return vc12 == null ? null : Math.round(vc12 * MBPS_PER_VC12 * 100.0) / 100.0;
    }

    /** 写入侧：库里仍存 VC-12 整数 */
    private static Double doubleOrNull(Integer value) {
        return value == null ? null : value.doubleValue();
    }

    /** 至少一端采集到了数据 */
    private boolean isCollected(LinkInspectionResult r) {
        return isNullOrEmpty(r.getAErrorInfo()) || isNullOrEmpty(r.getZErrorInfo());
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private void cleanupOldRounds() {
        try {
            List<InspectionRound> all = inspectionRoundRepository.findAll();
            if (all.size() > maxRounds) {
                all.sort(Comparator.comparing(InspectionRound::getStartTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed());
                for (int i = maxRounds; i < all.size(); i++) {
                    InspectionRound old = all.get(i);
                    List<LinkInspectionResult> oldResults = linkResultRepository.findByRoundId(old.getId());
                    if (!oldResults.isEmpty()) {
                        linkResultRepository.deleteAll(oldResults);
                    }
                    inspectionRoundRepository.delete(old);
                }
            }
        } catch (Exception e) {
            log.warn("清理超龄轮次失败: {}", e.getMessage());
        }
    }

    // ========== 协议字段解析 ==========

    /**
     * 将速率+距离档组合为标准光模块型号名（与老网管一致）
     * 例: 2.5G+L档 → "L16.1", 155M+I档 → "I1.1", GE+SX → "1000BASE-SX"
     */
    static String toModuleTypeName(int laserType, int distance) {
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
        String template = switch (distance) {
            case 1 -> "I{0}.1";
            case 2 -> (laserType == 4) ? "S{0}.2b" : "S{0}.1";
            case 3 -> (laserType == 4) ? "L{0}.2" : "L{0}.1";
            case 4 -> (laserType == 4) ? "V{0}.2" : "L{0}.2";
            default -> "Unknown(" + distance + ")";
        };
        return java.text.MessageFormat.format(template, speedCode);
    }

    /** 去掉网元类型名中的厂商标识前缀（如 MatrixEdge），只保留数字型号 */
    static String stripNeTypePrefix(String name) {
        if (name == null || name.isEmpty()) return name;
        return name.replaceAll("^[A-Za-z]+", "");
    }

    /**
     * 将设备返回的浮点光功率转换为 dBm。
     * 设备返回 NaN 表示无光功率读数（原始字节 0xFFFFFFFF）。
     */
    private static Double toOpticalPower(float rawPower) {
        return Float.isNaN(rawPower) ? null : (double) rawPower;
    }

    // ========== 小工具 ==========

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }

    private static Integer intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static String neIdOf(String portOid) {
        return portOid != null ? OidUtil.getNeOid(portOid) : null;
    }

    /** 链路某一端的静态信息 */
    private record LinkEnd(String portOid, String neId, String neName, String neTypeName, String portName,
                           Integer capacity, Integer used) {
    }

    /** 组装后写入 link_inspection_result 的某一端数据 */
    private record SideData(String neId, String neName, String neTypeName, String portOid, String portName,
                            String moduleType, Double txPower, Double rxPower, String txStatus, String rxStatus,
                            Integer rsErrorSec, Integer totalBandwidth, Integer usedBandwidth,
                            Double bandwidthUsage, String errorInfo) {
    }

    /** 单端口采集结果 */
    private record LaserSample(String moduleType, Double txPower, Double rxPower, String errorInfo) {

        static LaserSample of(LaserAttributeAckData ack) {
            if (ack == null) {
                return error("激光器查询无响应");
            }
            if ((ack.getSupportFlag() & LASER_SUPPORT_BIT) != 1) {
                return error("端口不支持光功率采集");
            }
            return new LaserSample(
                    toModuleTypeName(ack.getLaserType(), ack.getDistance()),
                    toOpticalPower(ack.getTranLaserPower()),
                    toOpticalPower(ack.getRecvLaserPower()),
                    null);
        }

        static LaserSample error(String message) {
            return new LaserSample(null, null, null, message);
        }
    }
}
