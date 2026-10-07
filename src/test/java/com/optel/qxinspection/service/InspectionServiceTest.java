package com.optel.qxinspection.service;

import com.optel.dc.ext.qx.service.QxCommandException;
import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.entity.sqlite.LinkInspectionResult;
import com.optel.qxinspection.laser.LaserAttributeGetRsp;
import com.optel.qxinspection.laser.service.ILaserService;
import com.optel.qxinspection.perf.PerfCurrent24HGetReq;
import com.optel.qxinspection.perf.service.IPerfService;
import com.optel.qxinspection.repository.sqlite.InspectionRoundRepository;
import com.optel.qxinspection.repository.sqlite.LinkInspectionResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 链路巡检服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class InspectionServiceTest {
    private static final String MODULE_LX = "1000BASE-LX";
    private static final String DEV_REJECT = "设备拒绝";

    @Mock
    private ILaserService laserService;

    @Mock
    private IPerfService perfService;

    @Mock
    private QxConnectionService qxConnectionService;

    @Mock
    private InspectionRoundRepository inspectionRoundRepository;

    @Mock
    private LinkInspectionResultRepository linkResultRepository;

    @Mock
    private JdbcTemplate sqliteJdbc;

    @Mock
    private ThresholdService thresholdService;

    @Mock
    private SysConfigService sysConfigService;

    @Mock
    private DynamicSyncService dynamicSyncService;

    @InjectMocks
    private InspectionService inspectionService;

    @Captor
    private ArgumentCaptor<List<LinkInspectionResult>> savedLinksCaptor;

    @BeforeEach
    void setUp() {
        lenient().when(inspectionRoundRepository.save(any(InspectionRound.class))).thenAnswer(invocation -> {
            InspectionRound round = invocation.getArgument(0);
            if (round.getId() == null) {
                round.setId(1L);
            }
            return round;
        });
        lenient().when(qxConnectionService.connectSingle(anyString()))
                .thenReturn(Map.of("success", false, "message", "mock 拒绝连接"));
        // 巡检前自动同步：测试时返回空列表跳过同步
        lenient().when(dynamicSyncService.getSyncedNetworkIds()).thenReturn(Collections.emptyList());
    }

    /** 一条链路记录（dmconnection cid=100 的列名） */
    private static Map<String, Object> linkRow() {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("oid", "1001");
        link.put("name", "NE-001-P1--NE-002-P1");
        link.put("aEnd", "101:1:11:2:1");
        link.put("zEnd", "102:1:11:2:1");
        link.put("aNetworkName", "骨干网A");
        link.put("aNeName", "NE-001");
        link.put("aNeTypeName", "MatrixEdge1830");
        link.put("aPortName", "P1(0/1/1)");
        link.put("aCapacity", 2500000);
        link.put("aUsed", 15000);
        link.put("zNetworkName", "骨干网A");
        link.put("zNeName", "NE-002");
        link.put("zNeTypeName", "MatrixEdge1830");
        link.put("zPortName", "P1(0/1/1)");
        link.put("zCapacity", 2500000);
        link.put("zUsed", 25000);
        link.put("linkCapacity", 0);
        link.put("linkUsed", 0);
        return link;
    }

    private void stubOneLink() {
        when(sqliteJdbc.queryForList(contains("dmconnection")))
                .thenReturn(List.of(linkRow()));
        lenient().when(sqliteJdbc.queryForList(contains("\"cid\" = 5")))
                .thenReturn(Collections.emptyList());
    }

    // ========== 触发巡检 ==========

    @Test
    void testTriggerInspectionAll_NoLinks_Throws() {
        when(sqliteJdbc.queryForList(anyString())).thenReturn(Collections.emptyList());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inspectionService.triggerInspectionAll());
        assertTrue(ex.getMessage().contains("未找到可巡检的链路"));
    }

    @Test
    void testTriggerInspectionAll_Success() {
        stubOneLink();

        InspectionRound result = inspectionService.triggerInspectionAll();

        assertNotNull(result);
        assertEquals("MANUAL", result.getTriggerType());
        assertEquals("ALL", result.getScopeType());
        assertEquals(1, result.getTotalCount());
        verify(inspectionRoundRepository, atLeast(1)).save(any(InspectionRound.class));
    }

    @Test
    void testTriggerInspectionByNetwork_Success() {
        stubOneLink();

        InspectionRound result = inspectionService.triggerInspectionByNetwork("骨干网A");

        assertEquals("NETWORK", result.getScopeType());
        assertEquals("骨干网A", result.getScopeParam());
    }

    @Test
    void testTriggerInspectionByNetwork_NoMatch_Throws() {
        stubOneLink();

        assertThrows(IllegalStateException.class,
                () -> inspectionService.triggerInspectionByNetwork("不存在的网络"));
    }

    @Test
    void testTriggerInspectionByNe_Success() {
        stubOneLink();

        InspectionRound result = inspectionService.triggerInspectionByNe("101");

        assertEquals("SINGLE", result.getScopeType());
        assertEquals("101", result.getScopeParam());
    }

    @Test
    void testTriggerInspectionByNe_NoMatch_Throws() {
        stubOneLink();

        assertThrows(IllegalStateException.class, () -> inspectionService.triggerInspectionByNe("9.9"));
    }

    @Test
    void testTriggerInspection_AlreadyRunning_Throws() {
        InspectionRound running = new InspectionRound();
        running.setId(7L);
        running.setStatus(InspectionRound.STATUS_RUNNING);
        ReflectionTestUtils.setField(inspectionService, "currentRound", running);

        assertThrows(IllegalStateException.class, () -> inspectionService.triggerInspectionAll());
    }

    // ========== 进度 ==========

    @Test
    void testGetProgress_NoRunningInspection() {
        Map<String, Object> result = inspectionService.getProgress();

        assertFalse((Boolean) result.get("running"));
    }

    @Test
    void testGetProgress_Running() {
        InspectionRound running = new InspectionRound();
        running.setId(9L);
        running.setStatus(InspectionRound.STATUS_RUNNING);
        running.setTotalCount(12);
        ReflectionTestUtils.setField(inspectionService, "currentRound", running);
        ReflectionTestUtils.setField(inspectionService, "progressCurrentNe", "NE-001");
        ReflectionTestUtils.setField(inspectionService, "progressCurrentPort", "PORT-1");

        Map<String, Object> result = inspectionService.getProgress();

        assertTrue((Boolean) result.get("running"));
        assertEquals(9L, result.get("roundId"));
        assertEquals(12, result.get("total"));
        assertEquals(0, result.get("done"));
        assertEquals("NE-001", result.get("currentNe"));
        assertEquals("PORT-1", result.get("currentPort"));
        assertNotNull(result.get("failures_list"));
    }

    // ========== 轮次列表 ==========

    @Test
    void testListRounds_Empty() {
        when(inspectionRoundRepository.findAll()).thenReturn(Collections.emptyList());

        assertTrue(inspectionService.listRounds().isEmpty());
    }

    @Test
    void testListRounds_WithRecords() {
        InspectionRound round1 = new InspectionRound();
        round1.setId(1L);
        InspectionRound round2 = new InspectionRound();
        round2.setId(2L);
        when(inspectionRoundRepository.findAll()).thenReturn(List.of(round2, round1));

        assertEquals(2, inspectionService.listRounds().size());
    }

    // ========== 链路结果查询 ==========

    @Test
    void testGetLinkResults_ByRoundId_AssignsSeqNo() {
        LinkInspectionResult r1 = new LinkInspectionResult();
        r1.setLinkName("L1");
        LinkInspectionResult r2 = new LinkInspectionResult();
        r2.setLinkName("L2");
        when(linkResultRepository.findByRoundId(3L)).thenReturn(new ArrayList<>(List.of(r1, r2)));

        List<LinkInspectionResult> result = inspectionService.getLinkResults(3L, null);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getSeqNo());
        assertEquals(2, result.get(1).getSeqNo());
    }

    @Test
    void testGetLinkResults_LatestRound_WhenRoundIdNull() {
        InspectionRound latest = new InspectionRound();
        latest.setId(5L);
        when(inspectionRoundRepository.findFirstByOrderByStartTimeDesc()).thenReturn(Optional.of(latest));
        when(linkResultRepository.findByRoundId(5L)).thenReturn(List.of(new LinkInspectionResult()));

        assertEquals(1, inspectionService.getLinkResults(null, null).size());
    }

    @Test
    void testGetLinkResults_NoRoundAtAll_ReturnsEmpty() {
        when(inspectionRoundRepository.findFirstByOrderByStartTimeDesc()).thenReturn(Optional.empty());

        assertTrue(inspectionService.getLinkResults(null, null).isEmpty());
    }

    @Test
    void testGetLinkResults_FilterByNetwork() {
        LinkInspectionResult r1 = new LinkInspectionResult();
        r1.setANeId("1.1");
        r1.setZNeId("1.2");
        LinkInspectionResult r2 = new LinkInspectionResult();
        r2.setANeId("2.1");
        r2.setZNeId("2.2");
        when(linkResultRepository.findByRoundId(4L)).thenReturn(new ArrayList<>(List.of(r1, r2)));
        when(sqliteJdbc.queryForList(contains("dmeo")))
                .thenReturn(List.of(Map.of("oid", "1.1", "networkName", "骨干网A"),
                        Map.of("oid", "1.2", "networkName", "骨干网A"),
                        Map.of("oid", "2.1", "networkName", "骨干网B"),
                        Map.of("oid", "2.2", "networkName", "骨干网B")));

        List<LinkInspectionResult> result = inspectionService.getLinkResults(4L, "骨干网A");

        assertEquals(1, result.size());
        assertEquals("1.1", result.get(0).getANeId());
        assertEquals(1, result.get(0).getSeqNo());
    }

    // ========== 带宽单位换算：库中存 VC-12 个数，对外一律 Mbps ==========

    /** 走一次 getLinkResults，返回换算后的 A 端总带宽 */
    private Double convertedATotal(Double vc12) {
        LinkInspectionResult r = new LinkInspectionResult();
        r.setATotalBandwidth(vc12);
        when(linkResultRepository.findByRoundId(99L)).thenReturn(new ArrayList<>(List.of(r)));
        return inspectionService.getLinkResults(99L, null).get(0).getATotalBandwidth();
    }

    @Test
    void testGetLinkResults_ConvertsBothEndsFromVc12ToMbps() {
        LinkInspectionResult r = new LinkInspectionResult();
        r.setATotalBandwidth(1008.0);
        r.setAUsedBandwidth(178.0);
        r.setZTotalBandwidth(252.0);
        r.setZUsedBandwidth(8.0);
        when(linkResultRepository.findByRoundId(7L)).thenReturn(new ArrayList<>(List.of(r)));

        LinkInspectionResult result = inspectionService.getLinkResults(7L, null).get(0);

        assertEquals(2064.38, result.getATotalBandwidth(), 0.01, "STM-16：1008 个 VC-12 → 2064.38 Mbps");
        assertEquals(364.54, result.getAUsedBandwidth(), 0.01);
        assertEquals(516.10, result.getZTotalBandwidth(), 0.01, "STM-4：252 个 VC-12 → 516.10 Mbps");
        assertEquals(16.38, result.getZUsedBandwidth(), 0.01);
    }

    @Test
    void testGetLinkResults_ConvertsEveryStmRate() {
        assertEquals(129.02, convertedATotal(63.0), 0.01, "STM-1：63 个 VC-12 → 129.02 Mbps");
        assertEquals(516.10, convertedATotal(252.0), 0.01, "STM-4：252 个 VC-12 → 516.10 Mbps");
        assertEquals(2064.38, convertedATotal(1008.0), 0.01, "STM-16：1008 个 VC-12 → 2064.38 Mbps");
        assertEquals(8257.54, convertedATotal(4032.0), 0.01, "STM-64：4032 个 VC-12 → 8257.54 Mbps");
    }

    @Test
    void testGetLinkResults_NullOrZeroBandwidthStaysUnchanged() {
        assertNull(convertedATotal(null), "null 应透传，不能变成 0");
        assertEquals(0.0, convertedATotal(0.0), 1e-9, "0 个 VC-12 仍是 0");
    }

    @Test
    void testGetLinkResults_BandwidthUsageIsNotRescaled() {
        LinkInspectionResult r = new LinkInspectionResult();
        r.setATotalBandwidth(1008.0);
        r.setAUsedBandwidth(178.0);
        r.setABandwidthUsage(17.7);
        when(linkResultRepository.findByRoundId(8L)).thenReturn(new ArrayList<>(List.of(r)));

        LinkInspectionResult result = inspectionService.getLinkResults(8L, null).get(0);

        assertEquals(17.7, result.getABandwidthUsage(), 1e-9, "利用率是比值，不带单位，不应被换算");
    }

    // ========== 摘要 ==========

    @Test
    void testGetSummary_NoData() {
        when(inspectionRoundRepository.findFirstByOrderByStartTimeDesc()).thenReturn(Optional.empty());

        Map<String, Object> result = inspectionService.getSummary();

        assertFalse((Boolean) result.get("hasData"));
    }

    @Test
    void testGetSummary_LinkCounters() {
        InspectionRound latest = new InspectionRound();
        latest.setId(2L);
        latest.setTotalCount(3);
        latest.setDoneCount(3);
        latest.setFailCount(0);
        latest.setStartTime(LocalDateTime.of(2026, 9, 18, 10, 0, 0));
        latest.setEndTime(LocalDateTime.of(2026, 9, 18, 10, 1, 0));
        when(inspectionRoundRepository.findFirstByOrderByStartTimeDesc()).thenReturn(Optional.of(latest));

        LinkInspectionResult normal = new LinkInspectionResult();
        normal.setATxStatus(ThresholdService.STATUS_NORMAL);
        normal.setARxStatus(ThresholdService.STATUS_NORMAL);
        normal.setZTxStatus(ThresholdService.STATUS_NORMAL);
        normal.setZRxStatus(ThresholdService.STATUS_NORMAL);
        normal.setAModuleType("S16.1");
        normal.setZModuleType("S16.1");
        normal.setANeTypeName("1830");
        normal.setZNeTypeName("1830");
        normal.setANeId("1.1");
        normal.setZNeId("1.2");

        LinkInspectionResult low = new LinkInspectionResult();
        low.setATxStatus(ThresholdService.STATUS_LOW);
        low.setARxStatus(ThresholdService.STATUS_NORMAL);
        low.setZTxStatus(ThresholdService.STATUS_NORMAL);
        low.setZRxStatus(ThresholdService.STATUS_NORMAL);
        low.setAModuleType("S16.1");
        low.setANeTypeName("1830");
        low.setANeId("1.3");
        low.setZNeId("1.2");

        LinkInspectionResult noLight = new LinkInspectionResult();
        noLight.setATxStatus(ThresholdService.STATUS_NO_LIGHT);
        noLight.setARxStatus(ThresholdService.STATUS_NORMAL);
        noLight.setZTxStatus(ThresholdService.STATUS_NORMAL);
        noLight.setZRxStatus(ThresholdService.STATUS_NORMAL);
        noLight.setZModuleType(MODULE_LX);
        noLight.setANeTypeName("1650");
        noLight.setZNeTypeName("1830");
        noLight.setANeId("1.4");
        noLight.setZNeId("1.2");

        when(linkResultRepository.findByRoundId(2L)).thenReturn(List.of(normal, low, noLight));

        Map<String, Object> result = inspectionService.getSummary();

        assertTrue((Boolean) result.get("hasData"));
        assertEquals(3, result.get("linkCount"));
        assertEquals(1L, result.get("normalLinks"));
        assertEquals(2L, result.get("abnormalLinks"));
        assertEquals(1L, result.get("noLightLinks"));
        assertEquals(60L, result.get("durationSec"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> byModule = (Map<String, Map<String, Object>>) result.get("byModuleType");
        assertEquals(3L, byModule.get("S16.1").get("count"));
        assertEquals(1L, byModule.get("S16.1").get("abnormal"));
        assertEquals(1L, byModule.get(MODULE_LX).get("count"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> byNeType = (Map<String, Map<String, Object>>) result.get("byNeType");
        assertEquals(4L, byNeType.get("1830").get("count"));
        assertEquals(1L, byNeType.get("1650").get("count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anomalies = (List<Map<String, Object>>) result.get("topAnomalies");
        assertEquals(2, anomalies.size());
        // 无光严重度最高，排在最前
        assertEquals(ThresholdService.STATUS_NO_LIGHT, anomalies.get(0).get("txStatus"));
    }

    @Test
    void testGetSummary_ErrorInfoCountsAsAbnormal() {
        InspectionRound latest = new InspectionRound();
        latest.setId(6L);
        latest.setStartTime(LocalDateTime.of(2026, 9, 18, 11, 0, 0));
        when(inspectionRoundRepository.findFirstByOrderByStartTimeDesc()).thenReturn(Optional.of(latest));

        LinkInspectionResult failed = new LinkInspectionResult();
        failed.setAErrorInfo("网元采集失败: 超时");
        failed.setATxStatus(ThresholdService.STATUS_NORMAL);
        failed.setARxStatus(ThresholdService.STATUS_NORMAL);
        failed.setZTxStatus(ThresholdService.STATUS_NORMAL);
        failed.setZRxStatus(ThresholdService.STATUS_NORMAL);
        when(linkResultRepository.findByRoundId(6L)).thenReturn(List.of(failed));

        Map<String, Object> result = inspectionService.getSummary();

        assertEquals(1L, result.get("abnormalLinks"));
        // 仅一端有 startTime，无 endTime，不输出耗时
        assertFalse(result.containsKey("durationSec"));
    }

    // ========== 采集参数 ==========

    @Test
    void testGetCollectParams() {
        Map<String, Object> result = inspectionService.getCollectParams();

        assertTrue(result.containsKey("concurrency"));
        assertTrue(result.containsKey("maxRounds"));
        assertTrue(result.containsKey("autoConnect"));
        assertTrue(result.containsKey("autoDisconnect"));
        assertTrue(result.containsKey("saveInvalid"));
    }

    @Test
    void testUpdateCollectParams_ClampsAndPersists() {
        inspectionService.updateCollectParams(999, 1, false, false, false);

        Map<String, Object> params = inspectionService.getCollectParams();
        assertEquals(50, params.get("concurrency"));
        assertEquals(5, params.get("maxRounds"));
        assertEquals(false, params.get("autoConnect"));
        verify(sysConfigService).set(eq("collect.concurrency"), eq("50"));
        verify(sysConfigService).set(eq("collect.maxRounds"), eq("5"));
    }

    @Test
    void testUpdateCollectParams_PersistFailureIsSwallowed() {
        doThrow(new IllegalStateException("db down"))
                .when(sysConfigService).set(anyString(), anyString());

        assertDoesNotThrow(() -> inspectionService.updateCollectParams(10, 10, true, true, true));
    }

    // ========== 采集成功判定（moduleType，性能失败不改端口成败） ==========

    /** 支持光功率的合法激光器回包 */
    private static LaserAttributeGetRsp laserOk() {
        return LaserAttributeGetRsp.builder()
                .supportFlag(1).laserType(0x10).distance(0x11)
                .tranLaserPower(1.5f).recvLaserPower(-2.0f)
                .build();
    }

    /** 同步跑完一轮采集（executeInspection 本身同步），saveInvalid=false 是本组用例的前提 */
    private InspectionRound runExecuteInspection(List<Map<String, Object>> links) {
        ReflectionTestUtils.setField(inspectionService, "concurrency", 4);
        ReflectionTestUtils.setField(inspectionService, "saveInvalid", false);
        InspectionRound round = new InspectionRound();
        round.setId(1L);
        round.setStatus(InspectionRound.STATUS_RUNNING);
        round.setStartTime(LocalDateTime.now());
        ReflectionTestUtils.invokeMethod(inspectionService, "executeInspection", round, links);
        return round;
    }

    @Test
    void testExecuteInspection_PerfFailBothEnds_StillSaved() {
        when(qxConnectionService.isConnected(anyString())).thenReturn(true);
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenThrow(new QxCommandException(1, DEV_REJECT));

        InspectionRound round = runExecuteInspection(List.of(linkRow()));

        // 性能失败只记 error_info，两端 moduleType 仍在 → 链路必须保存
        verify(linkResultRepository).saveAll(savedLinksCaptor.capture());
        assertEquals(1, savedLinksCaptor.getValue().size());
        LinkInspectionResult saved = savedLinksCaptor.getValue().get(0);
        assertNotNull(saved.getAModuleType());
        assertNotNull(saved.getZModuleType());
        assertTrue(saved.getAErrorInfo().startsWith(InspectionService.PERF_FAIL_PREFIX));
        assertTrue(saved.getZErrorInfo().startsWith(InspectionService.PERF_FAIL_PREFIX));
        assertEquals(1, round.getDoneCount());
    }

    @Test
    void testExecuteInspection_LaserFailBothEnds_NotSaved() {
        // isConnected 未 stub 默认 false，autoConnect 默认 true → connectSingle（setUp 统一 stub）
        // 返回 success=false → ensureConnected 抛异常走网元失败路径，moduleType=null
        InspectionRound round = runExecuteInspection(List.of(linkRow()));

        verify(linkResultRepository, never()).saveAll(any());
        assertEquals(0, round.getDoneCount());
    }

    @Test
    void testGetSummary_PerfFailNotCountedAsAbnormal() {
        InspectionRound latest = new InspectionRound();
        latest.setId(7L);
        when(inspectionRoundRepository.findFirstByOrderByStartTimeDesc()).thenReturn(Optional.of(latest));

        LinkInspectionResult perfFailed = new LinkInspectionResult();
        perfFailed.setAModuleType(MODULE_LX);
        perfFailed.setAErrorInfo(InspectionService.PERF_FAIL_PREFIX + DEV_REJECT);
        perfFailed.setATxStatus(ThresholdService.STATUS_NORMAL);
        perfFailed.setARxStatus(ThresholdService.STATUS_NORMAL);
        perfFailed.setZModuleType(MODULE_LX);
        perfFailed.setZErrorInfo(InspectionService.PERF_FAIL_PREFIX + DEV_REJECT);
        perfFailed.setZTxStatus(ThresholdService.STATUS_NORMAL);
        perfFailed.setZRxStatus(ThresholdService.STATUS_NORMAL);
        when(linkResultRepository.findByRoundId(7L)).thenReturn(List.of(perfFailed));

        Map<String, Object> result = inspectionService.getSummary();

        // 光功率门限全正常 → 性能失败不得把链路计入异常
        assertEquals(1L, result.get("normalLinks"));
        assertEquals(0L, result.get("abnormalLinks"));
        assertTrue(((List<?>) result.get("topAnomalies")).isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> byModule = (Map<String, Map<String, Object>>) result.get("byModuleType");
        assertEquals(2L, byModule.get(MODULE_LX).get("count"));
        assertEquals(0L, byModule.get(MODULE_LX).get("abnormal"));
    }

    @Test
    void testIsCollected_BothSidesAllNullDegenerateRow_ReturnsTrue() {
        // 退化行：endOf 返回 null 等导致 moduleType 与 error_info 两侧全 null —— sideCollected
        // 的"无 error_info 视为已采集"分支保证它仍按旧口径保存（见 sideCollected javadoc）
        LinkInspectionResult degenerate = new LinkInspectionResult();

        Boolean collected = ReflectionTestUtils.invokeMethod(inspectionService, "isCollected", degenerate);

        assertEquals(Boolean.TRUE, collected);
    }

    // ========== 静态工具 ==========

    @Test
    void testStripNeTypePrefix() {
        assertEquals("1830", InspectionService.stripNeTypePrefix("MatrixEdge1830"));
        assertEquals("1650", InspectionService.stripNeTypePrefix("1650"));
        assertNull(InspectionService.stripNeTypePrefix(null));
        assertEquals("", InspectionService.stripNeTypePrefix(""));
    }

    @Test
    void testToModuleTypeName() {
        assertEquals("1000BASE-SX", InspectionService.toModuleTypeName(0x10, 0x10));
        assertEquals(MODULE_LX, InspectionService.toModuleTypeName(0x10, 0x11));
        assertTrue(InspectionService.toModuleTypeName(0x10, 0x99).startsWith("GE-Unknown"));
        assertEquals("I16.1", InspectionService.toModuleTypeName(1, 1));
        assertEquals("S16.1", InspectionService.toModuleTypeName(1, 2));
        assertEquals("L16.1", InspectionService.toModuleTypeName(1, 3));
        assertEquals("L16.2", InspectionService.toModuleTypeName(1, 4));
        assertEquals("S64.2b", InspectionService.toModuleTypeName(4, 2));
        assertEquals("L64.2", InspectionService.toModuleTypeName(4, 3));
        assertEquals("V64.2", InspectionService.toModuleTypeName(4, 4));
        assertTrue(InspectionService.toModuleTypeName(1, 99).startsWith("Unknown"));
    }

    // ========== 波长拼接（moduleType 展示名 = 类型,波长） ==========

    @Test
    void testWaveName() {
        assertEquals("1310nm", InspectionService.LaserSample.waveName(1));
        assertEquals("1550nm", InspectionService.LaserSample.waveName(2));
        assertEquals("850nm", InspectionService.LaserSample.waveName(3));
        assertNull(InspectionService.LaserSample.waveName(9));
        assertNull(InspectionService.LaserSample.waveName(null));
    }

    @Test
    void testWithWave() {
        assertEquals("S4.1,1310nm", InspectionService.LaserSample.withWave("S4.1", "1310nm"));
        assertEquals("S4.1", InspectionService.LaserSample.withWave("S4.1", null));
        assertEquals("S4.1", InspectionService.LaserSample.withWave("S4.1", ""));
        assertNull(InspectionService.LaserSample.withWave(null, "1310nm"));
    }

    @Test
    void testLaserSampleOf_AppendsWave() {
        LaserAttributeGetRsp ack = LaserAttributeGetRsp.builder()
                .supportFlag(1).laserType(0x10).distance(0x11).laserWave(1)
                .tranLaserPower(1.5f).recvLaserPower(-2.0f)
                .build();

        InspectionService.LaserSample sample = InspectionService.LaserSample.of(List.of(ack));

        assertEquals("1000BASE-LX,1310nm", sample.moduleType());
    }

    @Test
    void testLaserSampleOf_NoWave_KeepsTypeOnly() {
        LaserAttributeGetRsp ack = LaserAttributeGetRsp.builder()
                .supportFlag(1).laserType(0x10).distance(0x11)
                .tranLaserPower(1.5f).recvLaserPower(-2.0f)
                .build();

        InspectionService.LaserSample sample = InspectionService.LaserSample.of(List.of(ack));

        assertEquals(MODULE_LX, sample.moduleType());
    }
}
