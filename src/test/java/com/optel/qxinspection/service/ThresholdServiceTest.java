package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import com.optel.qxinspection.repository.sqlite.ThresholdRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 门限服务单元测试（唯一预置源 + 实时判定）
 */
@ExtendWith(MockitoExtension.class)
class ThresholdServiceTest {

    /** 预置门限条目数（ThresholdService.PRESETS） */
    private static final int PRESET_COUNT = 15;

    @Mock
    private ThresholdRuleRepository thresholdRuleRepository;

    private ThresholdService thresholdService;

    @BeforeEach
    void setUp() {
        thresholdService = new ThresholdService(thresholdRuleRepository);
    }

    private static ThresholdRule rule(String matchKey, Double txLow, Double txHigh, Double rxLow, Double rxHigh) {
        ThresholdRule rule = new ThresholdRule();
        rule.setId(1L);
        rule.setMatchKey(matchKey);
        rule.setTxLow(txLow);
        rule.setTxHigh(txHigh);
        rule.setRxLow(rxLow);
        rule.setRxHigh(rxHigh);
        rule.setDescription("自定义");
        return rule;
    }

    // ========== initPresetThresholds ==========

    @Test
    void testInitPresetThresholds_InsertsAllMissing() {
        when(thresholdRuleRepository.findByMatchKey(anyString())).thenReturn(Optional.empty());

        thresholdService.initPresetThresholds();

        ArgumentCaptor<ThresholdRule> captor = ArgumentCaptor.forClass(ThresholdRule.class);
        verify(thresholdRuleRepository, times(PRESET_COUNT)).save(captor.capture());
        ThresholdRule first = captor.getAllValues().get(0);
        assertEquals("I1.1", first.getMatchKey());
        assertEquals(-10.0, first.getTxLow());
        assertEquals(0.0, first.getTxHigh());
        assertEquals(-28.0, first.getRxLow());
        assertEquals(-8.0, first.getRxHigh());
        assertEquals("155M+I档(短距)", first.getDescription());
    }

    @Test
    void testInitPresetThresholds_SkipsExisting() {
        when(thresholdRuleRepository.findByMatchKey(anyString()))
                .thenReturn(Optional.of(rule("I1.1", -10.0, 0.0, -28.0, -8.0)));

        thresholdService.initPresetThresholds();

        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
    }

    // ========== listRules ==========

    @Test
    void testListRules_Empty() {
        when(thresholdRuleRepository.findAll()).thenReturn(Collections.emptyList());

        assertTrue(thresholdService.listRules().isEmpty());
    }

    // ========== loadRangeMap ==========

    @Test
    void testLoadRangeMap_PresetsOnly() {
        when(thresholdRuleRepository.findAll()).thenReturn(Collections.emptyList());

        Map<String, ThresholdService.Range> ranges = thresholdService.loadRangeMap();

        assertEquals(PRESET_COUNT, ranges.size());
        ThresholdService.Range s16 = ranges.get("S16.1");
        assertEquals(-15.0, s16.txLow());
        assertEquals(-1.0, s16.txHigh());
        assertEquals(-28.0, s16.rxLow());
        assertEquals(-8.0, s16.rxHigh());
    }

    @Test
    void testLoadRangeMap_DbRuleOverridesPreset_WithNullFallback() {
        // txHigh 为 null → 回退到预置的 -1.0
        when(thresholdRuleRepository.findAll())
                .thenReturn(List.of(rule("S16.1", -6.0, null, -28.0, -8.0)));

        ThresholdService.Range range = thresholdService.loadRangeMap().get("S16.1");

        assertEquals(-6.0, range.txLow());
        assertEquals(-1.0, range.txHigh());
        assertEquals(-28.0, range.rxLow());
        assertEquals(-8.0, range.rxHigh());
    }

    @Test
    void testLoadRangeMap_UnknownMatchKey_UsesDefaultRange() {
        when(thresholdRuleRepository.findAll())
                .thenReturn(List.of(rule("NOT_A_PRESET", null, null, null, null)));

        ThresholdService.Range range = thresholdService.loadRangeMap().get("NOT_A_PRESET");

        assertEquals(-27.0, range.txLow());
        assertEquals(3.0, range.txHigh());
        assertEquals(-27.0, range.rxLow());
        assertEquals(3.0, range.rxHigh());
    }

    // ========== rangeFor ==========

    @Test
    void testRangeFor_MatchedKey() {
        Map<String, ThresholdService.Range> ranges = Map.of("L16.1", new ThresholdService.Range(-15, -1, -28, -8));

        ThresholdService.Range range = ThresholdService.rangeFor(ranges, "L16.1");

        assertEquals(-15.0, range.txLow());
    }

    @Test
    void testRangeFor_NullKeyAndAbsentKey_UseDefault() {
        Map<String, ThresholdService.Range> ranges = Map.of("L16.1", new ThresholdService.Range(-15, -1, -28, -8));

        assertEquals(3.0, ThresholdService.rangeFor(ranges, null).txHigh());
        assertEquals(3.0, ThresholdService.rangeFor(ranges, "UNKNOWN").txHigh());
    }

    // ========== evaluateStatus ==========

    @Test
    void testEvaluateStatus_NoLightOnNull() {
        assertEquals(ThresholdService.STATUS_NO_LIGHT,
                ThresholdService.evaluateStatus(null, -27.0, 3.0));
    }

    @Test
    void testEvaluateStatus_High() {
        assertEquals(ThresholdService.STATUS_HIGH,
                ThresholdService.evaluateStatus(3.1, -27.0, 3.0));
    }

    @Test
    void testEvaluateStatus_Low() {
        assertEquals(ThresholdService.STATUS_LOW,
                ThresholdService.evaluateStatus(-27.1, -27.0, 3.0));
    }

    @Test
    void testEvaluateStatus_NormalIncludingBoundaries() {
        assertEquals(ThresholdService.STATUS_NORMAL,
                ThresholdService.evaluateStatus(-27.0, -27.0, 3.0));
        assertEquals(ThresholdService.STATUS_NORMAL,
                ThresholdService.evaluateStatus(3.0, -27.0, 3.0));
        assertEquals(ThresholdService.STATUS_NORMAL,
                ThresholdService.evaluateStatus(-15.0, -27.0, 3.0));
    }

    // ========== updateRule ==========

    @Test
    void testUpdateRule_Success() {
        ThresholdRule existing = rule("L16.1", -15.0, -1.0, -28.0, -8.0);
        when(thresholdRuleRepository.findByMatchKey("L16.1")).thenReturn(Optional.of(existing));
        when(thresholdRuleRepository.save(existing)).thenReturn(existing);

        ThresholdRule changes = rule("L16.1", -6.0, 0.0, -28.0, -8.0);
        ThresholdRule updated = thresholdService.updateRule("L16.1", changes);

        assertEquals(-6.0, updated.getTxLow());
        assertEquals(0.0, updated.getTxHigh());
        assertEquals("自定义", updated.getDescription());
    }

    @Test
    void testUpdateRule_BlankDescriptionKeepsExisting() {
        ThresholdRule existing = rule("L16.1", -15.0, -1.0, -28.0, -8.0);
        existing.setDescription("原始说明");
        when(thresholdRuleRepository.findByMatchKey("L16.1")).thenReturn(Optional.of(existing));
        when(thresholdRuleRepository.save(existing)).thenReturn(existing);

        ThresholdRule changes = rule("L16.1", -6.0, 0.0, -28.0, -8.0);
        changes.setDescription("   ");

        assertEquals("原始说明", thresholdService.updateRule("L16.1", changes).getDescription());
    }

    @Test
    void testUpdateRule_UnknownMatchKey_Throws() {
        when(thresholdRuleRepository.findByMatchKey("NEW_TYPE")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> thresholdService.updateRule("NEW_TYPE", rule("NEW_TYPE", -10.0, 0.0, -10.0, 0.0)));
        assertTrue(ex.getMessage().contains("不允许新增"));
    }

    @Test
    void testUpdateRule_InvalidRange_Throws() {
        ThresholdRule existing = rule("L16.1", -15.0, -1.0, -28.0, -8.0);
        when(thresholdRuleRepository.findByMatchKey("L16.1")).thenReturn(Optional.of(existing));

        ThresholdRule lowAboveHigh = rule("L16.1", 0.0, -6.0, -28.0, -8.0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> thresholdService.updateRule("L16.1", lowAboveHigh));
        assertTrue(ex.getMessage().contains("低门限必须小于高门限"));
        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
    }

    @Test
    void testUpdateRule_NullThreshold_Throws() {
        ThresholdRule existing = rule("L16.1", -15.0, -1.0, -28.0, -8.0);
        when(thresholdRuleRepository.findByMatchKey("L16.1")).thenReturn(Optional.of(existing));

        ThresholdRule nullRxHigh = rule("L16.1", -6.0, 0.0, -28.0, null);
        assertThrows(IllegalArgumentException.class,
                () -> thresholdService.updateRule("L16.1", nullRxHigh));
    }

    // ========== listRuleViews ==========

    @Test
    void testListRuleViews_PresetOrderAndRates() {
        when(thresholdRuleRepository.findAll()).thenReturn(Collections.emptyList());

        List<ThresholdService.RuleView> views = thresholdService.listRuleViews();

        assertEquals(PRESET_COUNT, views.size());
        assertEquals("STM-1", views.get(0).rate());
        assertEquals("I1.1", views.get(0).matchKey());
        assertEquals(-10.0, views.get(0).range().txLow());
        assertEquals("GE", views.get(PRESET_COUNT - 1).rate());
        assertEquals("1000BASE-LX", views.get(PRESET_COUNT - 1).matchKey());
    }

    @Test
    void testListRuleViews_ReflectsDbOverride() {
        when(thresholdRuleRepository.findAll())
                .thenReturn(List.of(rule("L16.1", -6.0, 0.0, -28.0, -8.0)));

        ThresholdService.RuleView view = thresholdService.listRuleViews().stream()
                .filter(v -> "L16.1".equals(v.matchKey()))
                .findFirst()
                .orElseThrow();

        assertEquals(-6.0, view.range().txLow());
        assertEquals(0.0, view.range().txHigh());
    }
}
