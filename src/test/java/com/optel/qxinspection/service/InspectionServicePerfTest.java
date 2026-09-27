package com.optel.qxinspection.service;

import com.optel.qxinspection.perf.PerfCurrent24HGetRsp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0x0C07 性能回包解析（InspectionService.parsePerfResponses）单元测试
 */
class InspectionServicePerfTest {

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
}
