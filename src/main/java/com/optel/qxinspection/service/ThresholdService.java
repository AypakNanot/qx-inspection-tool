package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import com.optel.qxinspection.repository.sqlite.ThresholdRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门限判定服务。
 * <p>
 * 每个模块类型预置标准默认门限值，用户只能修改，不能添加或删除。
 * 门限在查询时实时计算，不持久化到巡检记录中。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThresholdService {

    /** 光功率状态：正常 */
    public static final String STATUS_NORMAL = "正常";
    /** 光功率状态：过高（过载） */
    public static final String STATUS_HIGH = "过高";
    /** 光功率状态：过低（劣化） */
    public static final String STATUS_LOW = "过低";
    /** 光功率状态：无光 */
    public static final String STATUS_NO_LIGHT = "无光";

    /** 模块类型未匹配到任何门限时使用的兜底区间 */
    private static final double DEFAULT_TX_LOW = -27.0;
    private static final double DEFAULT_TX_HIGH = 3.0;
    private static final double DEFAULT_RX_LOW = -27.0;
    private static final double DEFAULT_RX_HIGH = 3.0;

    private static final Range DEFAULT_RANGE =
            new Range(DEFAULT_TX_LOW, DEFAULT_TX_HIGH, DEFAULT_RX_LOW, DEFAULT_RX_HIGH);

    /**
     * 预置门限配置 —— 唯一的预置数据来源。
     * 用户只能修改数值，不能新增或删除模块类型。
     */
    private static final String RATE_STM1 = "STM-1";
    private static final String RATE_STM4 = "STM-4";
    private static final String RATE_STM16 = "STM-16";
    private static final String RATE_STM64 = "STM-64";
    private static final String RATE_GE = "GE";

    private static final List<Preset> PRESETS = List.of(
            new Preset(RATE_STM1, "I1.1", "155M+I档(短距)", -10.0, 0.0, -28.0, -8.0),
            new Preset(RATE_STM1, "S1.1", "155M+S档(中距)", -15.0, -1.0, -28.0, -8.0),
            new Preset(RATE_STM1, "L1.1", "155M+L档(长距)", -15.0, -1.0, -28.0, -8.0),
            new Preset(RATE_STM4, "I4.1", "622M+I档(短距)", -10.0, 0.0, -28.0, -8.0),
            new Preset(RATE_STM4, "S4.1", "622M+S档(中距)", -15.0, -1.0, -28.0, -8.0),
            new Preset(RATE_STM4, "L4.1", "622M+L档(长距)", -15.0, -1.0, -28.0, -8.0),
            new Preset(RATE_STM16, "I16.1", "2.5G+I档(短距)", -10.0, 0.0, -28.0, -8.0),
            new Preset(RATE_STM16, "S16.1", "2.5G+S档(中距)", -15.0, -1.0, -28.0, -8.0),
            new Preset(RATE_STM16, "L16.1", "2.5G+L档(长距)", -15.0, -1.0, -28.0, -8.0),
            new Preset(RATE_STM16, "V16.1", "2.5G+V档(超长距)", -15.0, -1.0, -28.0, -8.0),
            new Preset(RATE_STM64, "S64.2b", "10G+S档(中距)", -10.0, 0.0, -18.0, -1.0),
            new Preset(RATE_STM64, "L64.2", "10G+L档(长距)", -10.0, 0.0, -18.0, -1.0),
            new Preset(RATE_STM64, "V64.2", "10G+V档(超长距)", -10.0, 0.0, -18.0, -1.0),
            new Preset(RATE_GE, "1000BASE-SX", "GE+SX(多模)", -10.0, 0.0, -17.0, -3.0),
            new Preset(RATE_GE, "1000BASE-LX", "GE+LX(单模)", -10.0, 0.0, -17.0, -3.0)
    );

    private final ThresholdRuleRepository thresholdRuleRepository;

    /**
     * 初始化预置默认门限规则（仅插入数据库中不存在的）
     */
    @Transactional
    public void initPresetThresholds() {
        int inserted = 0;
        for (Preset preset : PRESETS) {
            if (thresholdRuleRepository.findByMatchKey(preset.matchKey()).isEmpty()) {
                ThresholdRule rule = new ThresholdRule();
                rule.setMatchKey(preset.matchKey());
                rule.setTxLow(preset.txLow());
                rule.setTxHigh(preset.txHigh());
                rule.setRxLow(preset.rxLow());
                rule.setRxHigh(preset.rxHigh());
                rule.setDescription(preset.description());
                thresholdRuleRepository.save(rule);
                inserted++;
            }
        }
        if (inserted > 0) {
            log.info("已初始化 {} 条预置门限规则", inserted);
        }
    }

    /** 查询全部门限规则（含预置值信息） */
    public List<Map<String, Object>> listRulesWithPresets() {
        Map<String, Preset> presetMap = new HashMap<>();
        for (Preset preset : PRESETS) {
            presetMap.put(preset.matchKey(), preset);
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (ThresholdRule rule : thresholdRuleRepository.findAll()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", rule.getId());
            entry.put("matchKey", rule.getMatchKey());
            entry.put("txLow", rule.getTxLow());
            entry.put("txHigh", rule.getTxHigh());
            entry.put("rxLow", rule.getRxLow());
            entry.put("rxHigh", rule.getRxHigh());
            entry.put("description", rule.getDescription());
            Preset preset = presetMap.get(rule.getMatchKey());
            if (preset != null) {
                entry.put("presetTxLow", preset.txLow());
                entry.put("presetTxHigh", preset.txHigh());
                entry.put("presetRxLow", preset.rxLow());
                entry.put("presetRxHigh", preset.rxHigh());
            }
            result.add(entry);
        }
        return result;
    }

    /** 查询全部门限规则 */
    public List<ThresholdRule> listRules() {
        return thresholdRuleRepository.findAll();
    }

    /**
     * 加载 matchKey → 门限区间 映射：预置值打底，数据库中的用户修改覆盖之。
     * <p>巡检过程中只查一次，避免逐条记录访问数据库。</p>
     */
    public Map<String, Range> loadRangeMap() {
        Map<String, Range> ranges = new HashMap<>();
        for (Preset preset : PRESETS) {
            ranges.put(preset.matchKey(), preset.range());
        }
        for (ThresholdRule rule : thresholdRuleRepository.findAll()) {
            Range preset = ranges.get(rule.getMatchKey());
            if (preset == null) {
                preset = DEFAULT_RANGE;
            }
            ranges.put(rule.getMatchKey(), new Range(
                    coalesce(rule.getTxLow(), preset.txLow()),
                    coalesce(rule.getTxHigh(), preset.txHigh()),
                    coalesce(rule.getRxLow(), preset.rxLow()),
                    coalesce(rule.getRxHigh(), preset.rxHigh())));
        }
        return ranges;
    }

    /**
     * 按模块类型取门限区间，未匹配时使用默认区间。
     */
    public static Range rangeFor(Map<String, Range> ranges, String moduleTypeKey) {
        Range range = moduleTypeKey != null ? ranges.get(moduleTypeKey) : null;
        return range != null ? range : DEFAULT_RANGE;
    }

    /**
     * 光功率状态判定：功率为空视为无光。
     */
    public static String evaluateStatus(Double power, double low, double high) {
        if (power == null) return STATUS_NO_LIGHT;
        if (power > high) return STATUS_HIGH;
        if (power < low) return STATUS_LOW;
        return STATUS_NORMAL;
    }

    /**
     * 修改门限规则数值（不允许新增模块类型）。
     */
    @Transactional
    public ThresholdRule updateRule(String matchKey, ThresholdRule changes) {
        ThresholdRule rule = thresholdRuleRepository.findByMatchKey(matchKey)
                .orElseThrow(() -> new IllegalArgumentException("门限规则不存在，不允许新增: " + matchKey));
        validateRange("发送", changes.getTxLow(), changes.getTxHigh());
        validateRange("接收", changes.getRxLow(), changes.getRxHigh());

        rule.setTxLow(changes.getTxLow());
        rule.setTxHigh(changes.getTxHigh());
        rule.setRxLow(changes.getRxLow());
        rule.setRxHigh(changes.getRxHigh());
        if (changes.getDescription() != null && !changes.getDescription().isBlank()) {
            rule.setDescription(changes.getDescription().trim());
        }
        return thresholdRuleRepository.save(rule);
    }

    private static void validateRange(String label, Double low, Double high) {
        if (low == null || high == null) {
            throw new IllegalArgumentException(label + "门限不能为空");
        }
        if (low >= high) {
            throw new IllegalArgumentException(label + "低门限必须小于高门限");
        }
    }

    private static double coalesce(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    /** 发送/接收门限区间 */
    public record Range(double txLow, double txHigh, double rxLow, double rxHigh) {
    }

    /** 门限规则视图：速率分组 + 模块类型 + 当前生效区间（用于导出说明表） */
    public record RuleView(String rate, String matchKey, String description, Range range) {
    }

    /**
     * 按预置顺序返回全部门限规则视图，数值以数据库中的用户修改为准。
     */
    public List<RuleView> listRuleViews() {
        Map<String, Range> ranges = loadRangeMap();
        return PRESETS.stream()
                .map(p -> new RuleView(p.rate(), p.matchKey(), p.description(), ranges.get(p.matchKey())))
                .toList();
    }

    /** 预置门限定义 */
    private record Preset(String rate, String matchKey, String description,
                          double txLow, double txHigh, double rxLow, double rxHigh) {
        Range range() {
            return new Range(txLow, txHigh, rxLow, rxHigh);
        }
    }
}
