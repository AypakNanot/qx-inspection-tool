package com.optel.qxinspection.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 门限预置值配置（app.threshold.presets）。
 * <p>
 * 预置值在 application.yml 中配置，用于门限规则的初始化与存量行刷新；
 * 用户在界面修改过的数值以数据库为准。type 必须与
 * {@code InspectionService.toModuleTypeName} 输出的模块类型键一致。
 * </p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.threshold")
public class ThresholdPresetProperties {

    /** 预置门限列表（顺序即界面/导出顺序） */
    private List<PresetItem> presets = new ArrayList<>();

    /** 单条预置门限 */
    @Getter
    @Setter
    public static class PresetItem {

        /** 速率分组（STM-1/STM-4/STM-16/STM-64） */
        private String rate;

        /** 模块类型匹配键（设备侧 toModuleTypeName 生成的名字） */
        private String type;

        /** 说明 */
        private String description;

        /** 发送光功率低门限 (dBm)。用包装类型：漏配时绑成 null 由校验拦截 */
        private Double txLow;

        /** 发送光功率高门限 (dBm) */
        private Double txHigh;

        /** 接收光功率低门限 (dBm) */
        private Double rxLow;

        /** 接收光功率高门限 (dBm) */
        private Double rxHigh;
    }
}
