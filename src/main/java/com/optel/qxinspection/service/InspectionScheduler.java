package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.InspectionRound;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定时巡检调度器
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class InspectionScheduler {

    private final InspectionService inspectionService;

    @Value("${app.inspection.scheduled.enabled:false}")
    private boolean enabled;

    @Value("${app.inspection.scheduled.scope:ALL}")
    private String scope;

    @Value("${app.inspection.scheduled.network:}")
    private String network;

    private volatile String lastRunStatus = "NEVER";
    private volatile String lastRunTime = "";

    /**
     * 定时巡检任务 - 使用 fixedDelay 由内部 cron 配置控制
     * 实际执行频率通过配置的 cron 表达式在 application.yml 中设置
     */
    @Scheduled(cron = "${app.inspection.scheduled.cron:0 0 2 * * ?}")
    public void scheduledInspection() {
        if (!enabled) {
            return;
        }

        log.info("定时巡检触发: scope={}, network={}", scope, network);
        try {
            InspectionRound round;
            if ("NETWORK".equals(scope) && !network.isEmpty()) {
                round = inspectionService.triggerInspectionByNetwork(network);
            } else {
                round = inspectionService.triggerInspectionAll();
            }
            lastRunStatus = "SUCCESS";
            lastRunTime = java.time.LocalDateTime.now().toString();
            log.info("定时巡检已提交: roundId={}", round.getId());
        } catch (IllegalStateException e) {
            lastRunStatus = "SKIPPED";
            lastRunTime = java.time.LocalDateTime.now().toString();
            log.warn("定时巡检跳过: {}", e.getMessage());
        } catch (Exception e) {
            lastRunStatus = "FAILED";
            lastRunTime = java.time.LocalDateTime.now().toString();
            log.error("定时巡检失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 查询定时巡检状态
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("scope", scope);
        status.put("network", network);
        status.put("lastRunStatus", lastRunStatus);
        status.put("lastRunTime", lastRunTime);
        return status;
    }

    /**
     * 动态启用/禁用定时巡检
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("定时巡检已{}", enabled ? "启用" : "禁用");
    }
}
