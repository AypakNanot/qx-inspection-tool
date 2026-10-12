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

    /** 精简预置集：S1.1 常规类型、L64.2 预置值与覆盖行、I64.1 仅预置无覆盖行 */
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

    // ========== removeStaleRules ==========

    @Test
    void testRemoveStaleRules_DeletesRowsNotInPresets() {
        // 配置外的键（历史死键/已废弃模块类型）清理，配置内的覆盖行保留
        ThresholdRule deadKey = rule("V16.1", -15.0, -1.0, -28.0, -8.0);
        ThresholdRule droppedType = rule("I1.1", -15.0, -8.0, -28.0, -8.0);
        ThresholdRule kept = rule("S1.1", -6.0, 0.0, -20.0, -5.0);
        when(thresholdRuleRepository.findAll()).thenReturn(List.of(deadKey, droppedType, kept));

        thresholdService.removeStaleRules();

        verify(thresholdRuleRepository).delete(deadKey);
        verify(thresholdRuleRepository).delete(droppedType);
        verify(thresholdRuleRepository, never()).delete(kept);
    }

    @Test
    void testRemoveStaleRules_AllRowsConfigured_NoDelete() {
        when(thresholdRuleRepository.findAll()).thenReturn(List.of(
                rule("S1.1", -6.0, 0.0, -20.0, -5.0),
                rule("L64.2", -2.0, 2.0, -26.0, -9.0),
                rule("I64.1", -6.0, -1.0, -11.0, -1.0)));

        thresholdService.removeStaleRules();

        verify(thresholdRuleRepository, never()).delete(any(ThresholdRule.class));
    }

    // ========== listRulesWithPresets ==========

    @Test
    void testListRulesWithPresets_EmptyDb_ShowsAllPresets() {
        // 库里没有任何覆盖行 → 全部展示预置默认值
        when(thresholdRuleRepository.findAll()).thenReturn(Collections.emptyList());

        List<Map<String, Object>> rules = thresholdService.listRulesWithPresets();

        assertEquals(PRESET_COUNT, rules.size());
        Map<String, Object> s11 = rules.get(0);
        assertEquals("S1.1", s11.get("matchKey"));
        assertNull(s11.get("id"), "无覆盖行时 id 应为空");
        assertEquals(-15.0, ((Double) s11.get("txLow")).doubleValue());
        assertEquals(-8.0, ((Double) s11.get("rxHigh")).doubleValue());
        assertEquals("155M+S档", s11.get("description"));
        assertEquals(-15.0, ((Double) s11.get("presetTxLow")).doubleValue());
    }

    @Test
    void testListRulesWithPresets_OverrideCoversFieldByField() {
        // 覆盖行只改部分字段 → 有值用行值，null 字段回落预置值，预置字段保持标准值
        ThresholdRule override = rule("S1.1", -6.0, null, -20.0, -5.0);
        override.setDescription("现场改过的");
        when(thresholdRuleRepository.findAll()).thenReturn(List.of(override));

        Map<String, Object> s11 = thresholdService.listRulesWithPresets().get(0);

        assertEquals(1L, s11.get("id"));
        assertEquals(-6.0, ((Double) s11.get("txLow")).doubleValue());
        assertEquals(-8.0, ((Double) s11.get("txHigh")).doubleValue());
        assertEquals(-15.0, ((Double) s11.get("presetTxLow")).doubleValue(), "预置字段始终是标准值");
        assertEquals("现场改过的", s11.get("description"));
    }

    @Test
    void testListRulesWithPresets_BlankOverrideDescription_FallsBackToPreset() {
        ThresholdRule override = rule("S1.1", -6.0, 0.0, -20.0, -5.0);
        override.setDescription("  ");
        when(thresholdRuleRepository.findAll()).thenReturn(List.of(override));

        Map<String, Object> s11 = thresholdService.listRulesWithPresets().get(0);

        assertEquals("155M+S档", s11.get("description"));
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
    void testUpdateRule_ModifiedValues_CreatesRowWhenAbsent() {
        // 覆盖层为空时的首次修改 → 直接建行；说明留空视为用默认说明，不入库
        when(thresholdRuleRepository.findByMatchKey("S1.1")).thenReturn(Optional.empty());
        when(thresholdRuleRepository.save(any(ThresholdRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ThresholdRule changes = rule("S1.1", -6.0, 0.0, -20.0, -5.0);
        changes.setDescription("   ");
        ThresholdRule saved = thresholdService.updateRule("S1.1", changes);

        ArgumentCaptor<ThresholdRule> captor = ArgumentCaptor.forClass(ThresholdRule.class);
        verify(thresholdRuleRepository).save(captor.capture());
        ThresholdRule row = captor.getValue();
        assertEquals("S1.1", row.getMatchKey());
        assertNull(row.getId(), "新行 id 由数据库分配");
        assertNull(row.getDescription(), "说明等于默认时不入库");
        assertEquals(-6.0, row.getTxLow().doubleValue());
        assertEquals(-5.0, row.getRxHigh().doubleValue());
        assertEquals(-6.0, saved.getTxLow().doubleValue());
    }

    @Test
    void testUpdateRule_ModifiedValues_UpdatesExistingRow() {
        ThresholdRule existing = rule("S1.1", -15.0, -8.0, -28.0, -8.0);
        when(thresholdRuleRepository.findByMatchKey("S1.1")).thenReturn(Optional.of(existing));
        when(thresholdRuleRepository.save(existing)).thenReturn(existing);

        ThresholdRule changes = rule("S1.1", -6.0, 0.0, -20.0, -5.0);
        changes.setDescription("现场改过的说明");
        ThresholdRule updated = thresholdService.updateRule("S1.1", changes);

        assertEquals(-6.0, existing.getTxLow().doubleValue());
        assertEquals("现场改过的说明", existing.getDescription());
        assertSame(existing, updated);
        verify(thresholdRuleRepository, never()).delete(any(ThresholdRule.class));
    }

    @Test
    void testUpdateRule_BackToDefault_DeletesRow() {
        // 改回与预置完全一致（说明留空 = 默认说明）→ 删行，恢复默认即删行
        ThresholdRule existing = rule("S1.1", -6.0, 0.0, -20.0, -5.0);
        existing.setDescription("现场改过的说明");
        when(thresholdRuleRepository.findByMatchKey("S1.1")).thenReturn(Optional.of(existing));

        ThresholdRule changes = rule("S1.1", -15.0, -8.0, -28.0, -8.0);
        changes.setDescription(null);
        ThresholdRule view = thresholdService.updateRule("S1.1", changes);

        verify(thresholdRuleRepository).delete(existing);
        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
        assertEquals(-15.0, view.getTxLow().doubleValue());
        assertEquals("155M+S档", view.getDescription());
    }

    @Test
    void testUpdateRule_DefaultValues_NoRow_WritesNothing() {
        // 已经是默认值且库里无行 → 不落库（无差异可存）
        when(thresholdRuleRepository.findByMatchKey("S1.1")).thenReturn(Optional.empty());

        ThresholdRule changes = rule("S1.1", -15.0, -8.0, -28.0, -8.0);
        changes.setDescription("155M+S档");
        ThresholdRule view = thresholdService.updateRule("S1.1", changes);

        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
        verify(thresholdRuleRepository, never()).delete(any(ThresholdRule.class));
        assertEquals("155M+S档", view.getDescription());
    }

    @Test
    void testUpdateRule_OnlyDescriptionDiffers_StoresRow() {
        // 数值全是默认、只改说明 → 依然要存行，否则自定义说明会丢
        when(thresholdRuleRepository.findByMatchKey("S1.1")).thenReturn(Optional.empty());
        when(thresholdRuleRepository.save(any(ThresholdRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ThresholdRule changes = rule("S1.1", -15.0, -8.0, -28.0, -8.0);
        changes.setDescription("只改说明");
        thresholdService.updateRule("S1.1", changes);

        ArgumentCaptor<ThresholdRule> captor = ArgumentCaptor.forClass(ThresholdRule.class);
        verify(thresholdRuleRepository).save(captor.capture());
        assertEquals("只改说明", captor.getValue().getDescription());
        assertEquals(-15.0, captor.getValue().getTxLow().doubleValue());
    }

    @Test
    void testUpdateRule_PresetDescriptionDiffersFromRow_NotStored() {
        // 保存的说明与预置说明相同 → 说明字段不入库（覆盖层只存差异）
        when(thresholdRuleRepository.findByMatchKey("S1.1")).thenReturn(Optional.empty());
        when(thresholdRuleRepository.save(any(ThresholdRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ThresholdRule changes = rule("S1.1", -6.0, 0.0, -20.0, -5.0);
        changes.setDescription("155M+S档");
        thresholdService.updateRule("S1.1", changes);

        ArgumentCaptor<ThresholdRule> captor = ArgumentCaptor.forClass(ThresholdRule.class);
        verify(thresholdRuleRepository).save(captor.capture());
        assertNull(captor.getValue().getDescription());
        assertEquals(-6.0, captor.getValue().getTxLow().doubleValue());
    }

    @Test
    void testUpdateRule_UnknownMatchKey_Throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> thresholdService.updateRule("NEW_TYPE", rule("NEW_TYPE", -10.0, 0.0, -10.0, 0.0)));
        assertTrue(ex.getMessage().contains("不允许新增"));
        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
    }

    @Test
    void testUpdateRule_InvalidRange_Throws() {
        ThresholdRule lowAboveHigh = rule("S1.1", 0.0, -6.0, -28.0, -8.0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> thresholdService.updateRule("S1.1", lowAboveHigh));
        assertTrue(ex.getMessage().contains("低门限必须小于高门限"));
        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
    }

    @Test
    void testUpdateRule_NullThreshold_Throws() {
        ThresholdRule nullRxHigh = rule("S1.1", -6.0, 0.0, -28.0, null);
        assertThrows(IllegalArgumentException.class,
                () -> thresholdService.updateRule("S1.1", nullRxHigh));
        verify(thresholdRuleRepository, never()).save(any(ThresholdRule.class));
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
