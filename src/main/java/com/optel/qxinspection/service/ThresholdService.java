package com.optel.qxinspection.service;

import com.optel.qxinspection.config.ThresholdPresetProperties;
import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import com.optel.qxinspection.repository.sqlite.ThresholdRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 门限判定服务。
 * <p>
 * 每个模块类型的预置默认门限值来自 application.yml 的 app.threshold.presets
 * （STM-1/4/16 取 ITU-T G.957 Table 2/3/4，STM-64 取 ITU-T G.691 应用参数），
 * 用户只能修改，不能添加或删除。门限在查询时实时计算，不持久化到巡检记录中。
 * </p>
 */
@Slf4j
@Service
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

    /** 配置前缀（校验报错消息用） */
    private static final String CONFIG_PREFIX = "app.threshold.presets";

    /**
     * 历史发布过的预置值快照，仅用于存量库迁移比对：
     * 行内四个数值与任一快照完全一致的视为未被人工修改，初始化时刷新为当前配置值。
     * <p>
     * 维护规则：以后修改 app.threshold.presets 中某 type 的数值时，必须把旧值追加到此表，
     * 否则存量库中停在旧值的行会被当作人工修改而永不刷新。
     * </p>
     */
    private static final Map<String, List<Range>> HISTORICAL_SNAPSHOTS = Map.ofEntries(
            // 预 G.957 时代的旧值
            Map.entry("S1.1", List.of(new Range(-15.0, -1.0, -28.0, -8.0))),
            Map.entry("L1.1", List.of(new Range(-15.0, -1.0, -28.0, -8.0))),
            Map.entry("S4.1", List.of(new Range(-15.0, -1.0, -28.0, -8.0))),
            Map.entry("L4.1", List.of(new Range(-15.0, -1.0, -28.0, -8.0))),
            Map.entry("I16.1", List.of(new Range(-10.0, 0.0, -18.0, -1.0))),
            Map.entry("S16.1", List.of(new Range(-15.0, -1.0, -18.0, -1.0))),
            Map.entry("L16.1", List.of(new Range(-15.0, -1.0, -28.0, -8.0))),
            // G.957 时代的 STM-64 值（本次改为 G.691 值）
            Map.entry("S64.2b", List.of(new Range(-10.0, 0.0, -18.0, -1.0))),
            Map.entry("L64.2", List.of(new Range(-10.0, 0.0, -18.0, -1.0))),
            Map.entry("V64.2", List.of(new Range(-10.0, 0.0, -18.0, -1.0))));

    private final ThresholdRuleRepository thresholdRuleRepository;

    /** 预置门限（YAML 顺序，界面/导出顺序） */
    private final List<Preset> presets;

    public ThresholdService(ThresholdRuleRepository thresholdRuleRepository,
                            ThresholdPresetProperties thresholdPresetProperties) {
        this.thresholdRuleRepository = thresholdRuleRepository;
        this.presets = buildPresets(thresholdPresetProperties.getPresets());
    }

    /** 校验配置并构建预置列表（启动即失败，避免坏配置带到巡检时才暴露） */
    private static List<Preset> buildPresets(List<ThresholdPresetProperties.PresetItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException(CONFIG_PREFIX + " 配置不能为空");
        }
        Set<String> seen = new HashSet<>();
        List<Preset> result = new ArrayList<>();
        for (ThresholdPresetProperties.PresetItem item : items) {
            validateItem(item);
            if (!seen.add(item.getType())) {
                throw new IllegalStateException(CONFIG_PREFIX + " 存在重复 type: " + item.getType());
            }
            result.add(new Preset(item.getRate(), item.getType(), item.getDescription(),
                    item.getTxLow(), item.getTxHigh(), item.getRxLow(), item.getRxHigh()));
        }
        return List.copyOf(result);
    }

    private static void validateItem(ThresholdPresetProperties.PresetItem item) {
        if (isBlank(item.getRate()) || isBlank(item.getType()) || isBlank(item.getDescription())) {
            throw new IllegalStateException(CONFIG_PREFIX + " 的 rate/type/description 不能为空: " + item.getType());
        }
        if (item.getTxLow() == null || item.getTxHigh() == null
                || item.getRxLow() == null || item.getRxHigh() == null) {
            throw new IllegalStateException(CONFIG_PREFIX + "[" + item.getType() + "] 门限不能为空");
        }
        try {
            validateRange("发送", item.getTxLow(), item.getTxHigh());
            validateRange("接收", item.getRxLow(), item.getRxHigh());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(CONFIG_PREFIX + "[" + item.getType() + "] " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 初始化预置默认门限规则。
     * <p>
     * 1) 缺失的键按配置补插；
     * 2) 存量行数值命中历史快照的（视为未被人工修改）刷新为配置值，人工改过的不动；
     * 3) 清理 matchKey 不在配置列表中的行（历史死键与已废弃模块类型）。
     * </p>
     */
    @Transactional
    public void initPresetThresholds() {
        int inserted = 0;
        int migrated = 0;
        for (Preset preset : presets) {
            Optional<ThresholdRule> existing = thresholdRuleRepository.findByMatchKey(preset.matchKey());
            if (existing.isEmpty()) {
                thresholdRuleRepository.save(newRule(preset));
                inserted++;
            } else if (refreshIfUntouched(existing.get(), preset)) {
                migrated++;
            }
        }
        int removed = deleteUnconfiguredRules();
        if (inserted > 0 || migrated > 0 || removed > 0) {
            log.info("预置门限初始化: 新增 {} 条, 迁移刷新 {} 条, 清理未配置键 {} 条", inserted, migrated, removed);
        }
    }

    private static ThresholdRule newRule(Preset preset) {
        ThresholdRule rule = new ThresholdRule();
        rule.setMatchKey(preset.matchKey());
        rule.setTxLow(preset.txLow());
        rule.setTxHigh(preset.txHigh());
        rule.setRxLow(preset.rxLow());
        rule.setRxHigh(preset.rxHigh());
        rule.setDescription(preset.description());
        return rule;
    }

    /**
     * 数值已等于当前配置 → 不动；数值命中历史快照（视为未被人工修改）→ 刷新为配置值。
     * 只刷新四个数值，不覆盖说明（说明可能被单独改过）。
     *
     * @return true 表示已刷新
     */
    private boolean refreshIfUntouched(ThresholdRule rule, Preset preset) {
        Range current = rangeOf(rule);
        if (preset.range().equals(current)) {
            return false;
        }
        List<Range> history = HISTORICAL_SNAPSHOTS.get(preset.matchKey());
        if (history == null || !history.contains(current)) {
            return false;
        }
        rule.setTxLow(preset.txLow());
        rule.setTxHigh(preset.txHigh());
        rule.setRxLow(preset.rxLow());
        rule.setRxHigh(preset.rxHigh());
        thresholdRuleRepository.save(rule);
        return true;
    }

    /** 删除 matchKey 不在当前预置列表中的行（历史死键与已废弃模块类型） */
    private int deleteUnconfiguredRules() {
        Set<String> configured = presets.stream().map(Preset::matchKey).collect(Collectors.toSet());
        List<ThresholdRule> stale = thresholdRuleRepository.findAll().stream()
                .filter(rule -> !configured.contains(rule.getMatchKey()))
                .toList();
        stale.forEach(thresholdRuleRepository::delete);
        return stale.size();
    }

    /** 取行内四个数值组成的区间；任一为空返回 null（与任何快照都不相等） */
    private static Range rangeOf(ThresholdRule rule) {
        if (rule.getTxLow() == null || rule.getTxHigh() == null
                || rule.getRxLow() == null || rule.getRxHigh() == null) {
            return null;
        }
        return new Range(rule.getTxLow(), rule.getTxHigh(), rule.getRxLow(), rule.getRxHigh());
    }

    /** 查询全部门限规则（含预置值信息） */
    public List<Map<String, Object>> listRulesWithPresets() {
        Map<String, Preset> presetMap = new HashMap<>();
        for (Preset preset : presets) {
            presetMap.put(preset.matchKey(), preset);
        }
        List<Map<String, Object>> result = new ArrayList<>();
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
        for (Preset preset : presets) {
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
        return presets.stream()
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
