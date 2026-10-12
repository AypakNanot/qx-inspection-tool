package com.optel.qxinspection.service;

import com.optel.qxinspection.config.ThresholdPresetProperties;
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
 * 门限服务单元测试（配置预置 + 实时判定）
 */
@ExtendWith(MockitoExtension.class)
class ThresholdServiceTest {

    /** fixture 预置条目数（S1.1 / L64.2 / I64.1） */
    private static final int PRESET_COUNT = 3;

    @Mock
    private ThresholdRuleRepository thresholdRuleRepository;

    private ThresholdService thresholdService;

    @BeforeEach
    void setUp() {
        thresholdService = new ThresholdService(thresholdRuleRepository, fixtureProperties());
    }

    /** 精简预置集：S1.1 覆盖常规刷新、L64.2 覆盖历史快照迁移、I64.1 覆盖无历史快照的新键 */
    private static ThresholdPresetProperties fixtureProperties() {
        return propertiesOf(
                item("STM-1", "S1.1", "155M+S档", -15.0, -8.0, -28.0, -8.0),
                item("STM-64", "L64.2", "10G+L档(长距,G.691 L-64.2a)", -2.0, 2.0, -26.0, -9.0),
                item("STM-64", "I64.1", "10G+I档(短距,G.691 I-64.1)", -6.0, -1.0, -11.0, -1.0));
    }

    private static ThresholdPresetProperties propertiesOf(ThresholdPresetProperties.PresetItem... items) {
        ThresholdPresetProperties properties = new ThresholdPresetProperties();
        properties.setPresets(List.of(items));
        return properties;
    }

    private static ThresholdPresetProperties.PresetItem item(String rate, String type, String description,
                                                             double txLow, double txHigh,
                                                             double rxLow, double rxHigh) {
        ThresholdPresetProperties.PresetItem item = new ThresholdPresetProperties.PresetItem();
        item.setRate(rate);
        item.setType(type);
        item.setDescription(description);
        item.setTxLow(txLow);
        item.setTxHigh(txHigh);
        item.setRxLow(rxLow);
        item.setRxHigh(rxHigh);
        return item;
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

    // ========== 构造校验 ==========

    @Test
    void testConstructor_EmptyPresets_Throws() {
        assertThrows(IllegalStateException.class,
                () -> new ThresholdService(thresholdRuleRepository, propertiesOf()));
    }

    @Test
    void testConstructor_DuplicateType_Throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ThresholdService(thresholdRuleRepository, propertiesOf(
                        item("STM-1", "S1.1", "a", -15.0, -8.0, -28.0, -8.0),
                        item("STM-1", "S1.1", "b", -5.0, 0.0, -30.0, -10.0))));
        assertTrue(ex.getMessage().contains("重复"));
    }

    @Test
    void testConstructor_InvalidRange_Throws() {
        // 发送低门限 >= 高门限
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ThresholdService(thresholdRuleRepository, propertiesOf(
                        item("STM-1", "S1.1", "a", -8.0, -15.0, -28.0, -8.0))));
        assertTrue(ex.getMessage().contains("S1.1"));
    }

    @Test
    void testConstructor_NullThreshold_Throws() {
        ThresholdPresetProperties.PresetItem nullRxHigh =
                item("STM-1", "S1.1", "a", -15.0, -8.0, -28.0, -8.0);
        nullRxHigh.setRxHigh(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ThresholdService(thresholdRuleRepository, propertiesOf(nullRxHigh)));
        assertTrue(ex.getMessage().contains("S1.1"));
    }

    @Test
    void testConstructor_BlankRateTypeDescription_Throws() {
        assertThrows(IllegalStateException.class,
                () -> new ThresholdService(thresholdRuleRepository, propertiesOf(
                        item(" ", "S1.1", "a", -15.0, -8.0, -28.0, -8.0))));
        assertThrows(IllegalStateException.class,
                () -> new ThresholdService(thresholdRuleRepository, propertiesOf(
                        item("STM-1", " ", "a", -15.0, -8.0, -28.0, -8.0))));
        assertThrows(IllegalStateException.class,
                () -> new ThresholdService(thresholdRuleRepository, propertiesOf(
                        item("STM-1", "S1.1", null, -15.0, -8.0, -28.0, -8.0))));
    }

    // ========== initPresetThresholds ==========

    @Test
    void testInitPresetThresholds_InsertsAllMissing() {
        when(thresholdRuleRepository.findByMatchKey(anyString())).thenReturn(Optional.empty());

        thresholdService.initPresetThresholds();

        ArgumentCaptor<ThresholdRule> captor = ArgumentCaptor.forClass(ThresholdRule.class);
        verify(thresholdRuleRepository, times(PRESET_COUNT)).save(captor.capture());
        ThresholdRule first = captor.getAllValues().get(0);
        assertEquals("S1.1", first.getMatchKey());
        assertEquals(-15.0, first.getTxLow());
        assertEquals(-8.0, first.getTxHigh());
        assertEquals(-28.0, first.getRxLow());
        assertEquals(-8.0, first.getRxHigh());
        assertEquals("155M+S档", first.getDescription());
    }

    @Test
    void testInitPresetThresholds_SkipsExisting_WhenValuesAlreadyCurrent() {
        when(thresholdRuleRepository.findByMatchKey("S1.1"))
                .thenReturn(Optional.of(rule("S1.1", -15.0, -8.0, -28.0, -8.0)));
        when(thresholdRuleRepository.findByMatchKey("L64.2"))
                .thenReturn(Optional.of(rule("L64.2", -2.0, 2.0, -26.0, -9.0)));
        when(thresholdRuleRepository.findByMatchKey("I64.1"))
                .thenReturn(Optional.of(rule("I64.1", -6.0, -1.0, -11.0, -1.0)));

        thresholdService.initPresetThresholds();

        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
        verify(thresholdRuleRepository, never()).delete(any(ThresholdRule.class));
    }

    @Test
    void testInitPresetThresholds_MigratesHistoricalRow_ToConfiguredValues() {
        // L64.2 存量值为 G.957 时代预置值 → 视为未人工修改，刷新为配置值（说明保持不动）
        when(thresholdRuleRepository.findByMatchKey(anyString())).thenReturn(Optional.empty());
        ThresholdRule historical = rule("L64.2", -10.0, 0.0, -18.0, -1.0);
        historical.setDescription("10G+L档(长距)");
        when(thresholdRuleRepository.findByMatchKey("L64.2")).thenReturn(Optional.of(historical));

        thresholdService.initPresetThresholds();

        ArgumentCaptor<ThresholdRule> captor = ArgumentCaptor.forClass(ThresholdRule.class);
        verify(thresholdRuleRepository, times(PRESET_COUNT)).save(captor.capture());
        ThresholdRule migrated = captor.getAllValues().stream()
                .filter(r -> "L64.2".equals(r.getMatchKey()))
                .findFirst().orElseThrow();
        assertEquals(-2.0, migrated.getTxLow());
        assertEquals(2.0, migrated.getTxHigh());
        assertEquals(-26.0, migrated.getRxLow());
        assertEquals(-9.0, migrated.getRxHigh());
        assertEquals("10G+L档(长距)", migrated.getDescription());
    }

    @Test
    void testInitPresetThresholds_PreservesUserModifiedRow() {
        // L64.2 数值与任何历史快照都不一致（人工改过）→ 保持不动
        when(thresholdRuleRepository.findByMatchKey(anyString())).thenReturn(Optional.empty());
        when(thresholdRuleRepository.findByMatchKey("L64.2"))
                .thenReturn(Optional.of(rule("L64.2", -10.0, 0.0, -18.0, -5.0)));

        thresholdService.initPresetThresholds();

        ArgumentCaptor<ThresholdRule> captor = ArgumentCaptor.forClass(ThresholdRule.class);
        verify(thresholdRuleRepository, times(PRESET_COUNT - 1)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(r -> "L64.2".equals(r.getMatchKey())));
    }

    @Test
    void testInitPresetThresholds_DeletesUnconfiguredKeys() {
        // 配置外的键（历史死键/已废弃模块类型）清理，配置内的键保留
        ThresholdRule deadKey = rule("V16.1", -15.0, -1.0, -28.0, -8.0);
        ThresholdRule droppedType = rule("I1.1", -15.0, -8.0, -28.0, -8.0);
        ThresholdRule geType = rule("1000BASE-SX", -10.0, 0.0, -17.0, -3.0);
        ThresholdRule kept = rule("S1.1", -15.0, -8.0, -28.0, -8.0);
        when(thresholdRuleRepository.findByMatchKey(anyString())).thenReturn(Optional.empty());
        when(thresholdRuleRepository.findByMatchKey("S1.1")).thenReturn(Optional.of(kept));
        when(thresholdRuleRepository.findAll())
                .thenReturn(List.of(deadKey, droppedType, geType, kept));

        thresholdService.initPresetThresholds();

        verify(thresholdRuleRepository).delete(deadKey);
        verify(thresholdRuleRepository).delete(droppedType);
        verify(thresholdRuleRepository).delete(geType);
        verify(thresholdRuleRepository, never()).delete(kept);
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
        ThresholdService.Range s11 = ranges.get("S1.1");
        assertEquals(-15.0, s11.txLow());
        assertEquals(-8.0, s11.txHigh());
        assertEquals(-28.0, s11.rxLow());
        assertEquals(-8.0, s11.rxHigh());
        // G.691 值（表中 L64.2a 行）
        ThresholdService.Range l642 = ranges.get("L64.2");
        assertEquals(-2.0, l642.txLow());
        assertEquals(2.0, l642.txHigh());
        assertEquals(-26.0, l642.rxLow());
        assertEquals(-9.0, l642.rxHigh());
        // 死键/废弃键不在预置中
        assertNull(ranges.get("V16.1"));
        assertNull(ranges.get("1000BASE-SX"));
    }

    @Test
    void testLoadRangeMap_DbRuleOverridesPreset_WithNullFallback() {
        // txHigh 为 null → 回退到预置值
        when(thresholdRuleRepository.findAll())
                .thenReturn(List.of(rule("S1.1", -6.0, null, -28.0, -8.0)));

        ThresholdService.Range range = thresholdService.loadRangeMap().get("S1.1");

        assertEquals(-6.0, range.txLow());
        assertEquals(-8.0, range.txHigh());
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

    @Test
    void testRangeFor_KeyWithWaveSuffix_StripsAfterComma() {
        Map<String, ThresholdService.Range> ranges = Map.of("L16.1", new ThresholdService.Range(-15, -1, -28, -8));

        ThresholdService.Range range = ThresholdService.rangeFor(ranges, "L16.1,1310nm");

        assertEquals(-15.0, range.txLow());
        assertEquals("L16.1", ThresholdService.matchKey("L16.1,1310nm"));
        assertEquals("L16.1", ThresholdService.matchKey("L16.1"));
        assertNull(ThresholdService.matchKey(null));
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
        assertEquals("S1.1", views.get(0).matchKey());
        assertEquals(-15.0, views.get(0).range().txLow());
        assertEquals("STM-64", views.get(PRESET_COUNT - 1).rate());
        assertEquals("I64.1", views.get(PRESET_COUNT - 1).matchKey());
    }

    @Test
    void testListRuleViews_ReflectsDbOverride() {
        when(thresholdRuleRepository.findAll())
                .thenReturn(List.of(rule("L64.2", -6.0, 0.0, -28.0, -8.0)));

        ThresholdService.RuleView view = thresholdService.listRuleViews().stream()
                .filter(v -> "L64.2".equals(v.matchKey()))
                .findFirst()
                .orElseThrow();

        assertEquals(-6.0, view.range().txLow());
        assertEquals(0.0, view.range().txHigh());
    }
}
