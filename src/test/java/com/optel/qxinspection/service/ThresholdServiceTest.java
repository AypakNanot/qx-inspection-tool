package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import com.optel.qxinspection.repository.sqlite.ThresholdRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 门限判定服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class ThresholdServiceTest {

    @Mock
    private ThresholdRuleRepository thresholdRuleRepository;

    private ThresholdService thresholdService;

    private ThresholdRule globalRule;
    private ThresholdRule moduleRule;

    @BeforeEach
    void setUp() {
        thresholdService = new ThresholdService(thresholdRuleRepository);

        // 全局门限: rxLow=-27, rxHigh=3, txLow=-27, txHigh=3
        globalRule = new ThresholdRule();
        globalRule.setId(1L);
        globalRule.setLevelType("GLOBAL");
        globalRule.setMatchKey("GLOBAL");
        globalRule.setRxLow(-27.0);
        globalRule.setRxHigh(3.0);
        globalRule.setTxLow(-27.0);
        globalRule.setTxHigh(3.0);

        // 模块类型门限: rxLow=-28, rxHigh=-8, txLow=-6, txHigh=0
        moduleRule = new ThresholdRule();
        moduleRule.setId(2L);
        moduleRule.setLevelType("MODULE");
        moduleRule.setMatchKey("L16.1");
        moduleRule.setRxLow(-28.0);
        moduleRule.setRxHigh(-8.0);
        moduleRule.setTxLow(-6.0);
        moduleRule.setTxHigh(0.0);
    }

    // ========== 全局门限匹配 ==========

    @Test
    void testApplyThresholds_WithGlobalRule() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-3.0);
        record.setRxPower(-15.0);
        record.setModuleTypeKey("S4.1"); // 无模块规则，使用全局

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getTxPowerStatus()); // -3在-27~3之间，正常
        assertEquals(0, result.get(0).getRxPowerStatus()); // -15在-27~3之间，正常
    }

    // ========== 模块类型门限匹配 ==========

    @Test
    void testApplyThresholds_WithModuleRule_Normal() {
        // L16.1模块门限: rxLow=-28, rxHigh=-8, txLow=-6, txHigh=0
        // rxPower=-15 在-28~-8之间 → 正常
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-3.0); // -3在-6~0之间 → 正常
        record.setRxPower(-15.0); // -15在-28~-8之间 → 正常
        record.setModuleTypeKey("L16.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Arrays.asList(globalRule, moduleRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(0, result.get(0).getRxPowerStatus());
    }

    @Test
    void testApplyThresholds_WithModuleRule_Degradation() {
        // L16.1模块门限: rxLow=-28, rxHigh=-8
        // rxPower=-30 < -28 → 劣化
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-3.0);
        record.setRxPower(-30.0);
        record.setModuleTypeKey("L16.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Arrays.asList(globalRule, moduleRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(1, result.get(0).getRxPowerStatus()); // 劣化
    }

    @Test
    void testApplyThresholds_WithModuleRule_Overload() {
        // L16.1模块门限: txLow=-6, txHigh=0
        // txPower=2.0 > 0 → 过载
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(2.0);
        record.setRxPower(-15.0);
        record.setModuleTypeKey("L16.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Arrays.asList(globalRule, moduleRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(2, result.get(0).getTxPowerStatus()); // 过载
        assertEquals(0, result.get(0).getRxPowerStatus());
    }

    // ========== 劣化状态判定 ==========

    @Test
    void testApplyThresholds_DegradationStatus_TxLow() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-30.0); // 低于-27
        record.setRxPower(-15.0);
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(1, result.get(0).getTxPowerStatus()); // 劣化
        assertEquals(0, result.get(0).getRxPowerStatus()); // 正常
    }

    @Test
    void testApplyThresholds_DegradationStatus_RxLow() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-3.0);
        record.setRxPower(-30.0); // 低于-27
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(1, result.get(0).getRxPowerStatus()); // 劣化
    }

    // ========== 过载状态判定 ==========

    @Test
    void testApplyThresholds_OverloadStatus_TxHigh() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(5.0); // 高于3.0
        record.setRxPower(-15.0);
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(2, result.get(0).getTxPowerStatus()); // 过载
        assertEquals(0, result.get(0).getRxPowerStatus());
    }

    @Test
    void testApplyThresholds_OverloadStatus_RxHigh() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-3.0);
        record.setRxPower(5.0); // 高于3.0
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(2, result.get(0).getRxPowerStatus()); // 过载
    }

    // ========== 不支持光功率的记录 ==========

    @Test
    void testApplyThresholds_UnsupportedRecord() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(false);
        record.setTxPower(null);
        record.setRxPower(null);

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(0, result.get(0).getRxPowerStatus());
    }

    // ========== 无门限规则（使用默认值） ==========

    @Test
    void testApplyThresholds_EmptyRules() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-3.0);
        record.setRxPower(-15.0);

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.emptyList());

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        // 使用默认值：txLow=-27, txHigh=3, rxLow=-27, rxHigh=3
        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(0, result.get(0).getRxPowerStatus());
    }

    // ========== 边界值测试 ==========

    @Test
    void testApplyThresholds_BoundaryValues_AtLowBoundary() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-27.0); // 等于低门限
        record.setRxPower(-27.0); // 等于低门限
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        // 等于低门限 → 正常（不触发劣化）
        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(0, result.get(0).getRxPowerStatus());
    }

    @Test
    void testApplyThresholds_BoundaryValues_AtHighBoundary() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(3.0); // 等于高门限
        record.setRxPower(3.0); // 等于高门限
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        // 等于高门限 → 正常（不触发过载）
        assertEquals(0, result.get(0).getTxPowerStatus());
        assertEquals(0, result.get(0).getRxPowerStatus());
    }

    @Test
    void testApplyThresholds_BoundaryValues_JustBelowLow() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-27.1); // 略低于低门限
        record.setRxPower(-27.1);
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(1, result.get(0).getTxPowerStatus()); // 劣化
        assertEquals(1, result.get(0).getRxPowerStatus()); // 劣化
    }

    @Test
    void testApplyThresholds_BoundaryValues_JustAboveHigh() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(3.1); // 略高于高门限
        record.setRxPower(3.1);
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        assertEquals(2, result.get(0).getTxPowerStatus()); // 过载
        assertEquals(2, result.get(0).getRxPowerStatus()); // 过载
    }

    // ========== 门限阈值字段验证 ==========

    @Test
    void testApplyThresholds_SetsThresholdFields() {
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(-3.0);
        record.setRxPower(-15.0);
        record.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        OpticalPowerInspection r = result.get(0);
        assertEquals(-27.0, r.getTxLowThreshold());
        assertEquals(3.0, r.getTxHighThreshold());
        assertEquals(-27.0, r.getLowThreshold());
        assertEquals(3.0, r.getHighThreshold());
    }

    // ========== 模块规则优先级验证 ==========

    @Test
    void testApplyThresholds_ModuleRuleOverridesGlobal() {
        // 同时有全局和模块规则，模块规则应生效
        // 模块门限: txLow=-6, txHigh=0, rxLow=-28, rxHigh=-8
        OpticalPowerInspection record = new OpticalPowerInspection();
        record.setSupported(true);
        record.setTxPower(1.0); // 1 > 0(模块txHigh) → 过载
        record.setRxPower(-3.0); // -3 > -8(模块rxHigh) → 过载
        record.setModuleTypeKey("L16.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Arrays.asList(globalRule, moduleRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Collections.singletonList(record));

        // 验证使用的是模块规则的阈值（而非全局规则）
        assertEquals(-6.0, result.get(0).getTxLowThreshold());
        assertEquals(0.0, result.get(0).getTxHighThreshold());
        assertEquals(-28.0, result.get(0).getLowThreshold());
        assertEquals(-8.0, result.get(0).getHighThreshold());
        assertEquals(2, result.get(0).getTxPowerStatus()); // 过载
        assertEquals(2, result.get(0).getRxPowerStatus()); // 过载
    }

    // ========== 多条记录批量处理 ==========

    @Test
    void testApplyThresholds_MultipleRecords() {
        OpticalPowerInspection r1 = new OpticalPowerInspection();
        r1.setSupported(true);
        r1.setTxPower(-3.0);
        r1.setRxPower(-15.0);
        r1.setModuleTypeKey("S4.1");

        OpticalPowerInspection r2 = new OpticalPowerInspection();
        r2.setSupported(true);
        r2.setTxPower(5.0);
        r2.setRxPower(-30.0);
        r2.setModuleTypeKey("S4.1");

        when(thresholdRuleRepository.findAll()).thenReturn(Collections.singletonList(globalRule));

        List<OpticalPowerInspection> result = thresholdService.applyThresholds(Arrays.asList(r1, r2));

        assertEquals(2, result.size());
        assertEquals(0, result.get(0).getTxPowerStatus()); // r1正常
        assertEquals(2, result.get(1).getTxPowerStatus()); // r2过载
        assertEquals(1, result.get(1).getRxPowerStatus()); // r2劣化
    }
}
