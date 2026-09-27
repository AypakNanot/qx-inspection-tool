package com.optel.qxinspection.service;

import com.optel.dc.ext.qx.service.QxCommandException;
import com.optel.qxinspection.laser.LaserAttributeGetRsp;
import com.optel.qxinspection.laser.service.ILaserService;
import com.optel.qxinspection.perf.PerfCurrent24HGetReq;
import com.optel.qxinspection.perf.PerfCurrent24HGetRsp;
import com.optel.qxinspection.perf.service.IPerfService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 0x0C07 性能回包解析（InspectionService.parsePerfResponses）与端口采集集成单元测试
 */
@ExtendWith(MockitoExtension.class)
class InspectionServicePerfTest {

    @Mock
    private ILaserService laserService;
    @Mock
    private IPerfService perfService;
    @InjectMocks
    private InspectionService inspectionService;

    /** 支持光功率的合法激光器回包 */
    private static LaserAttributeGetRsp laserOk() {
        return LaserAttributeGetRsp.builder()
                .supportFlag(1)
                .laserType(0x10)
                .distance(0x11)
                .tranLaserPower(1.5f)
                .recvLaserPower(-2.0f)
                .build();
    }

    private static final String PORT_OID = "101:1:11:2:1";

    /** 一次 0x0C07 的正常回包：三项各一条 */
    private static List<PerfCurrent24HGetRsp> perfOk(int es, int ses, int uas) {
        return List.of(
                rsp(0, PerfCodes.RS_ES, es),
                rsp(0, PerfCodes.RS_SES, ses),
                rsp(0, PerfCodes.RS_UAS, uas));
    }

    /** 合法回包一条（其余字段对解析无影响，留 null） */
    private static PerfCurrent24HGetRsp rsp(int subcaseNo, int code, int value) {
        return PerfCurrent24HGetRsp.builder()
                .subcaseNo(subcaseNo)
                .performanceCode(code)
                .performanceValue(value)
                .build();
    }

    @Test
    void parse_ThreeValidRecords_SumsAll() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 10),
                rsp(0, PerfCodes.RS_SES, 2),
                rsp(0, PerfCodes.RS_UAS, 1)));

        assertEquals(13, outcome.rsErrorSec());
        assertNull(outcome.issue());
    }

    @Test
    void parse_InvalidPmBit_SkipsRecordAndReportsMissing() {
        // bit7=1 无效记录跳过 → ES 缺失，SES+UAS 相加
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0x80, PerfCodes.RS_ES, 99),
                rsp(0, PerfCodes.RS_SES, 2),
                rsp(0, PerfCodes.RS_UAS, 1)));

        assertEquals(3, outcome.rsErrorSec());
        assertNotNull(outcome.issue());
        assertTrue(outcome.issue().contains("RS-ES"));
        assertTrue(outcome.issue().contains("性能采集缺失"));
    }

    @Test
    void parse_CodeAbsentFromResponse_ReportsMissing() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 10),
                rsp(0, PerfCodes.RS_UAS, 1)));

        assertEquals(11, outcome.rsErrorSec());
        assertTrue(outcome.issue().contains("RS-SES"));
    }

    @Test
    void parse_AllRecordsInvalid_ReturnsNullWithError() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0x80, PerfCodes.RS_ES, 10),
                rsp(0x80, PerfCodes.RS_SES, 2),
                rsp(0x80, PerfCodes.RS_UAS, 1)));

        assertNull(outcome.rsErrorSec());
        assertEquals(InspectionService.PERF_ALL_INVALID, outcome.issue());
    }

    @Test
    void parse_EmptyOrNullOrList_ReturnsNullWithError() {
        assertNull(InspectionService.parsePerfResponses(null).rsErrorSec());
        assertEquals(InspectionService.PERF_NO_DATA,
                InspectionService.parsePerfResponses(null).issue());

        InspectionService.PerfOutcome empty = InspectionService.parsePerfResponses(List.of());
        assertNull(empty.rsErrorSec());
        assertEquals(InspectionService.PERF_NO_DATA, empty.issue());
    }

    @Test
    void parse_HighWordBit_CombinesWithLowWord() {
        // bit6=1 的记录是高 32 位：值 = 低32位 + 高32位<<32，超 int 上限按 MAX_VALUE 防御
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 5),
                rsp(0x40, PerfCodes.RS_ES, 1),
                rsp(0, PerfCodes.RS_SES, 0),
                rsp(0, PerfCodes.RS_UAS, 0)));

        assertEquals(Integer.MAX_VALUE, outcome.rsErrorSec());
        assertNull(outcome.issue());
    }

    @Test
    void parse_HighWordOnly_NoLowWord_TreatedAsMissing() {
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0x40, PerfCodes.RS_ES, 1),
                rsp(0, PerfCodes.RS_SES, 2),
                rsp(0, PerfCodes.RS_UAS, 3)));

        assertEquals(5, outcome.rsErrorSec());
        assertTrue(outcome.issue().contains("RS-ES"));
    }

    @Test
    void parse_TwoCodesMissing_ReportsJoinedInOrder() {
        // 只返回 RS-UAS 一条有效记录 → 缺失两项按 ES/SES 顺序用 "/" 拼接
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_UAS, 7)));

        assertEquals(7, outcome.rsErrorSec());
        assertNotNull(outcome.issue());
        assertTrue(outcome.issue().contains("RS-ES/RS-SES"));
        assertTrue(outcome.issue().startsWith("性能采集缺失"));
    }

    @Test
    void parse_NullElementInList_Skipped() {
        List<PerfCurrent24HGetRsp> rsps = new ArrayList<>();
        rsps.add(null);
        rsps.add(rsp(0, PerfCodes.RS_ES, 10));
        rsps.add(rsp(0, PerfCodes.RS_SES, 2));
        rsps.add(rsp(0, PerfCodes.RS_UAS, 1));

        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(rsps);

        assertEquals(13, outcome.rsErrorSec());
        assertNull(outcome.issue());
    }

    @Test
    void parse_UasMissing_ReportsRsUasName() {
        // 缺 RS-UAS（ES/SES 在场）→ 求和已有两项，issue 标出 nameOf(RS_UAS) 分支
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 10),
                rsp(0, PerfCodes.RS_SES, 2)));

        assertEquals(12, outcome.rsErrorSec());
        assertNotNull(outcome.issue());
        // 只缺 UAS 一项：括号内仅 RS-UAS，ES/SES 不出现在缺失列表
        assertTrue(outcome.issue().contains("(RS-UAS)"));
        assertFalse(outcome.issue().contains("RS-ES"));
        assertFalse(outcome.issue().contains("RS-SES"));
    }

    @Test
    void parse_NegativeHighWord_TreatedAsUnsigned() {
        // 高 32 位按有符号解码为负值（脏数据）时须按无符号处理：-1 → 0xFFFFFFFF
        InspectionService.PerfOutcome outcome = InspectionService.parsePerfResponses(List.of(
                rsp(0, PerfCodes.RS_ES, 0),
                rsp(0x40, PerfCodes.RS_ES, -1),
                rsp(0, PerfCodes.RS_SES, 0),
                rsp(0, PerfCodes.RS_UAS, 0)));

        assertEquals(Integer.MAX_VALUE, outcome.rsErrorSec());
        assertNull(outcome.issue());
    }

    // ========== collectPort 与 0x0C07 性能采集集成 ==========

    @Test
    void collectPort_LaserFails_PerfNeverQueried() {
        when(laserService.attributeGet(anyString(), any())).thenThrow(new RuntimeException("boom"));

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertNotNull(sample.errorInfo());
        assertTrue(sample.errorInfo().contains("采集失败"));
        assertNull(sample.rsErrorSec());
        verifyNoInteractions(perfService);
    }

    @Test
    void collectPort_LaserNoResponse_PerfNeverQueried() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(Collections.emptyList());

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertEquals("激光器查询无响应", sample.errorInfo());
        verifyNoInteractions(perfService);
    }

    @Test
    void collectPort_LaserAndPerfOk_SumsErrorSeconds() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenReturn(perfOk(10, 2, 1));

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertNull(sample.errorInfo());
        assertEquals(13, sample.rsErrorSec());
        assertEquals(1.5, sample.txPower(), 1e-9);
    }

    @Test
    void collectPort_PerfRequestHasThreeCodesAndPhysicalPortParams() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenReturn(perfOk(0, 0, 0));

        inspectionService.collectPort("ne1", PORT_OID, Map.of());

        // varargs 整体按数组捕获：单元素 captor 在 verify 时与展开后的 3 个实参不匹配
        ArgumentCaptor<PerfCurrent24HGetReq[]> captor = ArgumentCaptor.forClass(PerfCurrent24HGetReq[].class);
        verify(perfService).current24HGet(eq("ne1"), captor.capture());
        PerfCurrent24HGetReq[] reqs = captor.getValue();
        assertEquals(3, reqs.length);
        List<Integer> codes = List.of(reqs).stream()
                .map(PerfCurrent24HGetReq::getPerformanceCode).sorted().toList();
        assertEquals(List.of(PerfCodes.RS_ES, PerfCodes.RS_SES, PerfCodes.RS_UAS), codes);
        for (PerfCurrent24HGetReq req : reqs) {
            assertEquals(0, req.getTsOrderId());
            assertEquals(0, req.getTsAttribute());
            assertEquals(0xFF, req.getPortType());
            assertEquals(0xFF, req.getPortSubType());
            assertEquals(1, req.getSubcaseNo());
            assertEquals(11, req.getSlotId());
            assertEquals(2, req.getPortId());
        }
    }

    @Test
    void collectPort_PerfThrows_ErrorInfoExplainsAndPortStillOk() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenThrow(new QxCommandException(1, "设备拒绝"));

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertNull(sample.rsErrorSec());
        assertTrue(sample.errorInfo().startsWith(InspectionService.PERF_FAIL_PREFIX));
        assertTrue(sample.errorInfo().contains("设备拒绝"));
        assertEquals(1.5, sample.txPower(), 1e-9);   // 光功率数据保留，端口仍是成功态
    }

    @Test
    void collectPort_PerfReturnsMissingMetric_StillOkWithIssue() {
        when(laserService.attributeGet(anyString(), any())).thenReturn(List.of(laserOk()));
        when(perfService.current24HGet(anyString(), any(PerfCurrent24HGetReq[].class)))
                .thenReturn(List.of(
                        rsp(0, PerfCodes.RS_ES, 10),
                        rsp(0, PerfCodes.RS_UAS, 1)));   // 缺 RS-SES

        InspectionService.LaserSample sample = inspectionService.collectPort("ne1", PORT_OID, Map.of());

        assertEquals(11, sample.rsErrorSec());
        assertTrue(sample.errorInfo().contains("RS-SES"));
    }
}
