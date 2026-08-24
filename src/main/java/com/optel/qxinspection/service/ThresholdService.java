package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import com.optel.qxinspection.repository.sqlite.ThresholdRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 门限判定服务
 * 匹配优先级: PART > MODULE > GLOBAL
 * 门限在查询时实时计算，不持久化到记录中
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThresholdService {

    private final ThresholdRuleRepository thresholdRuleRepository;

    // 默认门限值
    private static final double DEFAULT_TX_LOW = -27.0;
    private static final double DEFAULT_TX_HIGH = 3.0;
    private static final double DEFAULT_RX_LOW = -27.0;
    private static final double DEFAULT_RX_HIGH = 3.0;

    /**
     * 为记录列表批量应用门限判定（查询时调用）
     */
    public List<OpticalPowerInspection> applyThresholds(List<OpticalPowerInspection> records) {
        List<ThresholdRule> allRules = thresholdRuleRepository.findAll();
        Map<String, ThresholdRule> ruleMap = new HashMap<>();
        for (ThresholdRule rule : allRules) {
            String key = rule.getLevelType() + ":" + rule.getMatchKey();
            ThresholdRule prev = ruleMap.put(key, rule);
            if (prev != null) {
                log.warn("重复门限规则: {}:{}, 使用id={}", rule.getLevelType(), rule.getMatchKey(), rule.getId());
            }
        }

        ThresholdRule globalRule = ruleMap.get("GLOBAL:GLOBAL");

        for (OpticalPowerInspection r : records) {
            if (!Boolean.TRUE.equals(r.getSupported())) {
                continue;
            }
            ThresholdRule matched = matchRule(ruleMap, globalRule, r);
            evaluateRecord(r, matched);
        }
        return records;
    }

    /**
     * 匹配门限规则: PART > MODULE > GLOBAL
     */
    private ThresholdRule matchRule(Map<String, ThresholdRule> ruleMap,
                                     ThresholdRule globalRule,
                                     OpticalPowerInspection record) {
        // PART级: 按partNumber匹配
        if (record.getPartNumber() != null && !record.getPartNumber().isEmpty()) {
            ThresholdRule partRule = ruleMap.get("PART:" + record.getPartNumber());
            if (partRule != null) return partRule;
        }
        // MODULE级: 按moduleTypeKey匹配
        if (record.getModuleTypeKey() != null && !record.getModuleTypeKey().isEmpty()) {
            ThresholdRule moduleRule = ruleMap.get("MODULE:" + record.getModuleTypeKey());
            if (moduleRule != null) return moduleRule;
        }
        // GLOBAL级
        return globalRule;
    }

    /**
     * 对单条记录应用门限判定
     */
    private void evaluateRecord(OpticalPowerInspection r, ThresholdRule rule) {
        double txLow = (rule != null && rule.getTxLow() != null) ? rule.getTxLow() : DEFAULT_TX_LOW;
        double txHigh = (rule != null && rule.getTxHigh() != null) ? rule.getTxHigh() : DEFAULT_TX_HIGH;
        double rxLow = (rule != null && rule.getRxLow() != null) ? rule.getRxLow() : DEFAULT_RX_LOW;
        double rxHigh = (rule != null && rule.getRxHigh() != null) ? rule.getRxHigh() : DEFAULT_RX_HIGH;

        r.setLowThreshold(rxLow);
        r.setHighThreshold(rxHigh);
        r.setTxLowThreshold(txLow);
        r.setTxHighThreshold(txHigh);

        if (r.getTxPower() != null) {
            if (r.getTxPower() < txLow) r.setTxPowerStatus(1);
            else if (r.getTxPower() > txHigh) r.setTxPowerStatus(2);
            else r.setTxPowerStatus(0);
        }
        if (r.getRxPower() != null) {
            if (r.getRxPower() < rxLow) r.setRxPowerStatus(1);
            else if (r.getRxPower() > rxHigh) r.setRxPowerStatus(2);
            else r.setRxPowerStatus(0);
        }
    }

    /**
     * 获取当前门限快照（用于导出）
     */
    public Map<String, Object> getThresholdSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<ThresholdRule> allRules = thresholdRuleRepository.findAll();

        ThresholdRule global = allRules.stream()
                .filter(r -> "GLOBAL".equals(r.getLevelType()))
                .findFirst().orElse(null);

        snapshot.put("global", global != null ? ruleToMap(global) : getDefaultRuleMap());

        List<Map<String, Object>> moduleRules = allRules.stream()
                .filter(r -> "MODULE".equals(r.getLevelType()))
                .map(this::ruleToMap)
                .toList();
        snapshot.put("module", moduleRules);

        List<Map<String, Object>> partRules = allRules.stream()
                .filter(r -> "PART".equals(r.getLevelType()))
                .map(this::ruleToMap)
                .toList();
        snapshot.put("part", partRules);

        return snapshot;
    }

    private Map<String, Object> ruleToMap(ThresholdRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matchKey", r.getMatchKey());
        m.put("txLow", r.getTxLow());
        m.put("txHigh", r.getTxHigh());
        m.put("rxLow", r.getRxLow());
        m.put("rxHigh", r.getRxHigh());
        m.put("description", r.getDescription());
        return m;
    }

    private Map<String, Object> getDefaultRuleMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matchKey", "GLOBAL");
        m.put("txLow", DEFAULT_TX_LOW);
        m.put("txHigh", DEFAULT_TX_HIGH);
        m.put("rxLow", DEFAULT_RX_LOW);
        m.put("rxHigh", DEFAULT_RX_HIGH);
        m.put("description", "默认门限");
        return m;
    }

    /**
     * 初始化默认GLOBAL规则
     */
    public void initDefaultGlobalRule() {
        if (thresholdRuleRepository.findByLevelTypeAndMatchKey("GLOBAL", "GLOBAL").isEmpty()) {
            ThresholdRule global = new ThresholdRule();
            global.setLevelType("GLOBAL");
            global.setMatchKey("GLOBAL");
            global.setTxLow(DEFAULT_TX_LOW);
            global.setTxHigh(DEFAULT_TX_HIGH);
            global.setRxLow(DEFAULT_RX_LOW);
            global.setRxHigh(DEFAULT_RX_HIGH);
            global.setDescription("默认全局门限");
            thresholdRuleRepository.save(global);
            log.info("已初始化默认GLOBAL门限规则");
        }
    }
}
