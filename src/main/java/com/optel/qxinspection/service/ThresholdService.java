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
import java.util.Optional;

/**
 * 门限判定服务。
 * <p>
 * 每个模块类型预置标准默认门限值（STM-1/4/16 按 ITU-T G.957 Table 2/3/4），用户只能修改，不能添加或删除。
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

    /*
     * STM-1/4/16 值取自 ITU-T G.957 Table 2/3/4（应用参数表）：
     * 发送 = 源输出功率 min/max，接收 = [灵敏度, 过载]。
     * matchKey 沿用设备侧 toModuleTypeName 生成的键（非标准连字符码），
     * 标准应用码写在 description。STM-64/GE 不在 G.957 范围
     * （分别见 G.691 / IEEE 802.3），维持原值。
     */
    private static final List<Preset> PRESETS = List.of(
            // ITU-T G.957 Table 2 -- STM-1
            new Preset(RATE_STM1, "I1.1", "155M+I档(短距,G.957 I-1)", -15.0, -8.0, -23.0, -8.0),
            new Preset(RATE_STM1, "S1.1", "155M+S档(中距,G.957 S-1.1/S-1.2)", -15.0, -8.0, -28.0, -8.0),
            new Preset(RATE_STM1, "L1.1", "155M+L档(长距,G.957 L-1.1~L-1.3)", -5.0, 0.0, -34.0, -10.0),
            new Preset(RATE_STM1, "L1.2", "155M+V档(超长距,G.957 L-1.2)", -5.0, 0.0, -34.0, -10.0),
            // ITU-T G.957 Table 3 -- STM-4
            new Preset(RATE_STM4, "I4.1", "622M+I档(短距,G.957 I-4)", -15.0, -8.0, -23.0, -8.0),
            new Preset(RATE_STM4, "S4.1", "622M+S档(中距,G.957 S-4.1/S-4.2)", -15.0, -8.0, -28.0, -8.0),
            new Preset(RATE_STM4, "L4.1", "622M+L档(长距,G.957 L-4.1~L-4.3)", -3.0, 2.0, -28.0, -8.0),
            new Preset(RATE_STM4, "L4.2", "622M+V档(超长距,G.957 L-4.2)", -3.0, 2.0, -28.0, -8.0),
            // ITU-T G.957 Table 4 -- STM-16
            new Preset(RATE_STM16, "I16.1", "2.5G+I档(短距,G.957 I-16)", -10.0, -3.0, -18.0, -3.0),
            new Preset(RATE_STM16, "S16.1", "2.5G+S档(中距,G.957 S-16.1/S-16.2)", -5.0, 0.0, -18.0, 0.0),
            new Preset(RATE_STM16, "L16.1", "2.5G+L档(长距,G.957 L-16.1)", -2.0, 3.0, -27.0, -9.0),
            new Preset(RATE_STM16, "L16.2", "2.5G+V档(超长距,G.957 L-16.2)", -2.0, 3.0, -28.0, -9.0),
            // G.957 不覆盖的类型，维持原值
            new Preset(RATE_STM64, "S64.2b", "10G+S档(中距)", -10.0, 0.0, -18.0, -1.0),
            new Preset(RATE_STM64, "L64.2", "10G+L档(长距)", -10.0, 0.0, -18.0, -1.0),
            new Preset(RATE_STM64, "V64.2", "10G+V档(超长距)", -10.0, 0.0, -18.0, -1.0),
            new Preset(RATE_GE, "1000BASE-SX", "GE+SX(多模)", -10.0, 0.0, -17.0, -3.0),
            new Preset(RATE_GE, "1000BASE-LX", "GE+LX(单模)", -10.0, 0.0, -17.0, -3.0)
    );

    /**
     * 按 G.957 整改前的旧预置值，仅用于存量库迁移比对：
     * 行内四个数值与之完全一致的视为未被人工修改，初始化时刷新为标准值。
     */
    private static final Map<String, Range> LEGACY_PRESETS = Map.ofEntries(
            Map.entry("I1.1", new Range(-10.0, 0.0, -28.0, -8.0)),
            Map.entry("S1.1", new Range(-15.0, -1.0, -28.0, -8.0)),
            Map.entry("L1.1", new Range(-15.0, -1.0, -28.0, -8.0)),
            Map.entry("I4.1", new Range(-10.0, 0.0, -28.0, -8.0)),
            Map.entry("S4.1", new Range(-15.0, -1.0, -28.0, -8.0)),
            Map.entry("L4.1", new Range(-15.0, -1.0, -28.0, -8.0)),
            Map.entry("I16.1", new Range(-10.0, 0.0, -18.0, -1.0)),
            Map.entry("S16.1", new Range(-15.0, -1.0, -18.0, -1.0)),
            Map.entry("L16.1", new Range(-15.0, -1.0, -28.0, -8.0)));

    /** G.957 中不存在、设备侧也永不生成的死键（旧版误加），初始化时清理 */
    private static final String DEAD_KEY = "V16.1";

    private final ThresholdRuleRepository thresholdRuleRepository;

    /**
     * 初始化预置默认门限规则。
     * <p>
     * 1) 缺失的键补插（含按 G.957 新增的 L1.2/L4.2/L16.2）；
     * 2) 存量行数值与旧预置完全一致的（视为未人工修改）刷新为 G.957 标准值；
     * 3) 清理设备永不生成的死键 V16.1。
     * </p>
     */
    @Transactional
    public void initPresetThresholds() {
        int inserted = 0;
        int migrated = 0;
        for (Preset preset : PRESETS) {
            Optional<ThresholdRule> existing = thresholdRuleRepository.findByMatchKey(preset.matchKey());
            if (existing.isEmpty()) {
                ThresholdRule rule = new ThresholdRule();
                rule.setMatchKey(preset.matchKey());
                rule.setTxLow(preset.txLow());
                rule.setTxHigh(preset.txHigh());
                rule.setRxLow(preset.rxLow());
                rule.setRxHigh(preset.rxHigh());
                rule.setDescription(preset.description());
                thresholdRuleRepository.save(rule);
                inserted++;
                continue;
            }
            ThresholdRule rule = existing.get();
            Range legacy = LEGACY_PRESETS.get(preset.matchKey());
            if (legacy != null && matchesLegacy(rule, legacy)) {
                rule.setTxLow(preset.txLow());
                rule.setTxHigh(preset.txHigh());
                rule.setRxLow(preset.rxLow());
                rule.setRxHigh(preset.rxHigh());
                thresholdRuleRepository.save(rule);
                migrated++;
            }
        }
        int removed = 0;
        for (ThresholdRule rule : thresholdRuleRepository.findAll()) {
            if (DEAD_KEY.equals(rule.getMatchKey())) {
                thresholdRuleRepository.delete(rule);
                removed++;
            }
        }
        if (inserted > 0 || migrated > 0 || removed > 0) {
            log.info("预置门限初始化: 新增 {} 条, 迁移为 G.957 标准值 {} 条, 删除死键 {} 条",
                    inserted, migrated, removed);
        }
    }

    /** 四个数值与旧预置完全一致才视为未被人工修改 */
    private static boolean matchesLegacy(ThresholdRule rule, Range legacy) {
        return rule.getTxLow() != null && rule.getTxLow() == legacy.txLow()
                && rule.getTxHigh() != null && rule.getTxHigh() == legacy.txHigh()
                && rule.getRxLow() != null && rule.getRxLow() == legacy.rxLow()
                && rule.getRxHigh() != null && rule.getRxHigh() == legacy.rxHigh();
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
     * moduleTypeKey 可能带波长后缀（如 "S4.1,1310nm"），匹配前截到逗号前的纯类型。
     */
    public static Range rangeFor(Map<String, Range> ranges, String moduleTypeKey) {
        String key = matchKey(moduleTypeKey);
        Range range = key != null ? ranges.get(key) : null;
        return range != null ? range : DEFAULT_RANGE;
    }

    /** 门限匹配键 = 模块类型第一个逗号前的部分 */
    static String matchKey(String moduleTypeKey) {
        if (moduleTypeKey == null) {
            return null;
        }
        int comma = moduleTypeKey.indexOf(',');
        return comma >= 0 ? moduleTypeKey.substring(0, comma) : moduleTypeKey;
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
