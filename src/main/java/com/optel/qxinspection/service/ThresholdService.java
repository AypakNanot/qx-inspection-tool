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
 * 门限默认值的唯一权威是 application.yml 的 app.threshold.presets
 * （STM-1/4/16 取 ITU-T G.957 Table 2/3/4，STM-64 取 ITU-T G.691 应用参数），
 * 预置类型用户只能改，不能添加或删除。
 * </p>
 * <p>
 * threshold_rule 表是覆盖层，只保存用户改出来的差异：库里没有的类型直接用预置值；
 * 查询时预置值打底、库行按字段覆盖。保存结果与默认完全一致时不落库——已有行则删除
 * （恢复默认即删行），因此库中永远只有"偏离标准的项"，预置值更新后用户没改过的类型自动跟随。
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

    private final ThresholdRuleRepository thresholdRuleRepository;

    /** 预置门限（YAML 顺序，界面/导出顺序） */
    private final List<Preset> presets;

    /** 预置门限按 matchKey 索引 */
    private final Map<String, Preset> presetMap;

    public ThresholdService(ThresholdRuleRepository thresholdRuleRepository,
                            ThresholdPresetProperties thresholdPresetProperties) {
        this.thresholdRuleRepository = thresholdRuleRepository;
        this.presets = buildPresets(thresholdPresetProperties.getPresets());
        this.presetMap = presets.stream()
                .collect(Collectors.toUnmodifiableMap(Preset::matchKey, p -> p));
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
     * 清理配置中已不存在的 matchKey 覆盖行（模块类型从配置删除后遗留的孤儿行）。
     * 预置类型不会被种进库——没有覆盖行就说明用户没改过。
     */
    @Transactional
    public void removeStaleRules() {
        Set<String> configured = presets.stream().map(Preset::matchKey).collect(Collectors.toSet());
        List<ThresholdRule> stale = thresholdRuleRepository.findAll().stream()
                .filter(rule -> !configured.contains(rule.getMatchKey()))
                .toList();
        stale.forEach(thresholdRuleRepository::delete);
        if (!stale.isEmpty()) {
            log.info("清理未配置的门限覆盖行: {} 条", stale.size());
        }
    }

    /**
     * 查询全部门限规则视图（预置值打底 + 覆盖行按字段覆盖，按预置顺序返回）。
     * id 为 null 表示该类型没有覆盖行，展示的就是标准默认值。
     */
    public List<Map<String, Object>> listRulesWithPresets() {
        Map<String, ThresholdRule> overrides = new HashMap<>();
        for (ThresholdRule rule : thresholdRuleRepository.findAll()) {
            overrides.put(rule.getMatchKey(), rule);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Preset preset : presets) {
            ThresholdRule override = overrides.get(preset.matchKey());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", override != null ? override.getId() : null);
            entry.put("matchKey", preset.matchKey());
            entry.put("txLow", valueOr(override != null ? override.getTxLow() : null, preset.txLow()));
            entry.put("txHigh", valueOr(override != null ? override.getTxHigh() : null, preset.txHigh()));
            entry.put("rxLow", valueOr(override != null ? override.getRxLow() : null, preset.rxLow()));
            entry.put("rxHigh", valueOr(override != null ? override.getRxHigh() : null, preset.rxHigh()));
            entry.put("description", descriptionOf(override, preset));
            entry.put("presetTxLow", preset.txLow());
            entry.put("presetTxHigh", preset.txHigh());
            entry.put("presetRxLow", preset.rxLow());
            entry.put("presetRxHigh", preset.rxHigh());
            result.add(entry);
        }
        return result;
    }

    /**
     * 加载 matchKey → 门限区间 映射：预置值打底，覆盖行按字段覆盖之。
     * <p>巡检过程中只查一次，避免逐条记录访问数据库。</p>
     */
    public Map<String, Range> loadRangeMap() {
        Map<String, Range> ranges = new HashMap<>();
        for (Preset preset : presets) {
            ranges.put(preset.matchKey(), preset.range());
        }
        for (ThresholdRule rule : thresholdRuleRepository.findAll()) {
            Range base = ranges.getOrDefault(rule.getMatchKey(), DEFAULT_RANGE);
            ranges.put(rule.getMatchKey(), new Range(
                    valueOr(rule.getTxLow(), base.txLow()),
                    valueOr(rule.getTxHigh(), base.txHigh()),
                    valueOr(rule.getRxLow(), base.rxLow()),
                    valueOr(rule.getRxHigh(), base.rxHigh())));
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
     * 保存用户修改（只能改预置中存在的模块类型，不能新增类型）。
     * <p>
     * 四个数值与说明都等于预置默认时不落库：已有行则删除，恢复默认即删行。
     * 说明留空视为使用默认说明，说明等于预置说明时不入库。
     * </p>
     *
     * @return 生效后的门限规则视图（等于默认时返回预置值，未落库）
     */
    @Transactional
    public ThresholdRule updateRule(String matchKey, ThresholdRule changes) {
        Preset preset = presetOf(matchKey);
        validateRange("发送", changes.getTxLow(), changes.getTxHigh());
        validateRange("接收", changes.getRxLow(), changes.getRxHigh());

        String description = normalizeDescription(preset, changes.getDescription());
        Range submitted = new Range(changes.getTxLow(), changes.getTxHigh(),
                changes.getRxLow(), changes.getRxHigh());
        Optional<ThresholdRule> existing = thresholdRuleRepository.findByMatchKey(matchKey);
        if (description == null && preset.range().equals(submitted)) {
            existing.ifPresent(thresholdRuleRepository::delete);
            return defaultRule(preset);
        }

        ThresholdRule rule = existing.orElseGet(ThresholdRule::new);
        rule.setMatchKey(matchKey);
        rule.setTxLow(changes.getTxLow());
        rule.setTxHigh(changes.getTxHigh());
        rule.setRxLow(changes.getRxLow());
        rule.setRxHigh(changes.getRxHigh());
        rule.setDescription(description);
        return thresholdRuleRepository.save(rule);
    }

    private Preset presetOf(String matchKey) {
        Preset preset = presetMap.get(matchKey);
        if (preset == null) {
            throw new IllegalArgumentException("门限规则不存在，不允许新增: " + matchKey);
        }
        return preset;
    }

    /** 说明留空或与预置说明相同 → 返回 null（使用默认说明，不入库） */
    private static String normalizeDescription(Preset preset, String description) {
        if (isBlank(description)) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.equals(preset.description()) ? null : trimmed;
    }

    /** 库行说明为空则回落到预置说明 */
    private static String descriptionOf(ThresholdRule override, Preset preset) {
        if (override == null || isBlank(override.getDescription())) {
            return preset.description();
        }
        return override.getDescription();
    }

    /** 覆盖行字段有值时用行值，否则回落到预置值（按字段合并） */
    private static double valueOr(Double value, double preset) {
        return value != null ? value : preset;
    }

    /** 构造默认值视图（未落库，仅供返回给调用方） */
    private static ThresholdRule defaultRule(Preset preset) {
        ThresholdRule rule = new ThresholdRule();
        rule.setMatchKey(preset.matchKey());
        rule.setTxLow(preset.txLow());
        rule.setTxHigh(preset.txHigh());
        rule.setRxLow(preset.rxLow());
        rule.setRxHigh(preset.rxHigh());
        rule.setDescription(preset.description());
        return rule;
    }

    private static void validateRange(String label, Double low, Double high) {
        if (low == null || high == null) {
            throw new IllegalArgumentException(label + "门限不能为空");
        }
        if (low >= high) {
            throw new IllegalArgumentException(label + "低门限必须小于高门限");
        }
    }

    /** 发送/接收门限区间 */
    public record Range(double txLow, double txHigh, double rxLow, double rxHigh) {
    }

    /** 门限规则视图：速率分组 + 模块类型 + 当前生效区间（用于导出说明表） */
    public record RuleView(String rate, String matchKey, String description, Range range) {
    }

    /**
     * 按预置顺序返回全部门限规则视图，数值以覆盖行的用户修改为准。
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
