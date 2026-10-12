package com.optel.qxinspection.config;

import com.optel.qxinspection.repository.sqlite.ThresholdRuleRepository;
import com.optel.qxinspection.service.ThresholdService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 application.yml 的门限预置值与需求表一致。
 * <p>需求表列序是 发光过高/发光过低/收光过高/收光过低，与 YAML 字段序（tx-low 在前）不同，转写易错，
 * 故按真实 relaxed binding 绑定后逐条比对。</p>
 */
class ThresholdPresetYamlTest {

    /** 需求表期望值（列序与需求表一致：txHigh, txLow, rxHigh, rxLow） */
    private record Expected(String rate, String type,
                            double txHigh, double txLow, double rxHigh, double rxLow) {
    }

    private static final List<Expected> EXPECTED = List.of(
            new Expected("STM-1", "S1.1", -8, -15, -8, -28),
            new Expected("STM-1", "L1.1", 0, -5, -10, -34),
            new Expected("STM-1", "L1.2", 0, -5, -10, -34),
            new Expected("STM-4", "S4.1", -8, -15, -8, -28),
            new Expected("STM-4", "L4.1", 2, -3, -8, -28),
            new Expected("STM-4", "L4.2", 2, -3, -8, -28),
            new Expected("STM-16", "I16.1", -3, -10, -3, -18),
            new Expected("STM-16", "S16.1", 0, -5, 0, -18),
            new Expected("STM-16", "L16.1", 3, -2, -9, -27),
            new Expected("STM-16", "L16.2", 3, -2, -9, -28),
            new Expected("STM-64", "I64.1", -1, -6, -1, -11),
            new Expected("STM-64", "S64.2b", 2, -1, -1, -14),
            new Expected("STM-64", "L64.2", 2, -2, -9, -26),
            new Expected("STM-64", "V64.2", 2, -2, -9, -26));

    private static ThresholdPresetProperties bound;

    @BeforeAll
    static void bindApplicationYml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        bound = binder.bind("app.threshold", ThresholdPresetProperties.class)
                .orElseThrow(() -> new AssertionError("application.yml 缺少 app.threshold 配置"));
    }

    @Test
    void presetsMatchRequirementTable() {
        List<ThresholdPresetProperties.PresetItem> presets = bound.getPresets();
        assertEquals(EXPECTED.size(), presets.size());
        for (int i = 0; i < EXPECTED.size(); i++) {
            assertRow(EXPECTED.get(i), presets.get(i));
        }
    }

    private static void assertRow(Expected expected, ThresholdPresetProperties.PresetItem item) {
        String type = expected.type();
        assertEquals(expected.rate(), item.getRate(), type + " rate");
        assertEquals(type, item.getType());
        assertEquals(expected.txHigh(), item.getTxHigh().doubleValue(), type + " txHigh");
        assertEquals(expected.txLow(), item.getTxLow().doubleValue(), type + " txLow");
        assertEquals(expected.rxHigh(), item.getRxHigh().doubleValue(), type + " rxHigh");
        assertEquals(expected.rxLow(), item.getRxLow().doubleValue(), type + " rxLow");
    }

    @Test
    void rateGroupsMatchRequirement() {
        assertEquals(3, countOf("STM-1"));
        assertEquals(3, countOf("STM-4"));
        assertEquals(4, countOf("STM-16"));
        assertEquals(4, countOf("STM-64"));
    }

    private static long countOf(String rate) {
        return bound.getPresets().stream().filter(p -> rate.equals(p.getRate())).count();
    }

    @Test
    void descriptionsCarryStandardCodes() {
        for (ThresholdPresetProperties.PresetItem item : bound.getPresets()) {
            assertFalse(item.getDescription() == null || item.getDescription().isBlank(),
                    item.getType() + " description 不能为空");
        }
        // 设备键 L64.2 / V64.2 对应 G.691 L-64.2a / L-64.2c
        assertTrue(descriptionOf("L64.2").contains("L-64.2a"), descriptionOf("L64.2"));
        assertTrue(descriptionOf("V64.2").contains("L-64.2c"), descriptionOf("V64.2"));
    }

    private static String descriptionOf(String type) {
        return bound.getPresets().stream()
                .filter(p -> type.equals(p.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 " + type))
                .getDescription();
    }

    @Test
    void thresholdsAreOrdered() {
        for (ThresholdPresetProperties.PresetItem item : bound.getPresets()) {
            assertTrue(item.getTxLow() < item.getTxHigh(), item.getType() + " 发送低门限必须小于高门限");
            assertTrue(item.getRxLow() < item.getRxHigh(), item.getType() + " 接收低门限必须小于高门限");
        }
    }

    @Test
    void configurationPassesServiceValidation() {
        assertDoesNotThrow(() -> new ThresholdService(Mockito.mock(ThresholdRuleRepository.class), bound));
    }

    @Test
    void missingConfigurationIsRejected() {
        ThresholdPresetProperties empty = new ThresholdPresetProperties();
        assertThrows(IllegalStateException.class,
                () -> new ThresholdService(Mockito.mock(ThresholdRuleRepository.class), empty));
    }
}
