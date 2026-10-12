package com.optel.qxinspection;

import com.optel.qxinspection.config.ThresholdPresetProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * QX设备直连巡检工具启动类
 *
 * @author Rwj
 * @since 2026-08-20
 */
@SpringBootApplication
@EnableConfigurationProperties(ThresholdPresetProperties.class)
public class QxInspectionToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(QxInspectionToolApplication.class, args);
    }
}
