package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.InspectionRound;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时巡检调度器（配置持久化到SQLite）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionScheduler implements InitializingBean {

    private static final String KEY_ENABLED = "schedule.enabled";
    private static final String KEY_SCOPE = "schedule.scope";
    private static final String KEY_NETWORK = "schedule.network";
    private static final String KEY_CRON = "schedule.cron";
    private static final String KEY_LAST_RUN_STATUS = "schedule.lastRunStatus";
    private static final String KEY_LAST_RUN_TIME = "schedule.lastRunTime";

    private final InspectionService inspectionService;
    private final SysConfigService sysConfigService;
    private final TaskScheduler taskScheduler;

    private volatile boolean enabled;
    private volatile String scope = "ALL";
    private volatile String network = "";
    private volatile String cronExpression = "0 0 2 * * ?";
    private volatile String lastRunStatus = "NEVER";
    private volatile String lastRunTime = "";

    private ScheduledFuture<?> scheduledTask;

    @Override
    public void afterPropertiesSet() {
        // 从数据库加载配置（覆盖默认值）
        enabled = Boolean.parseBoolean(sysConfigService.get(KEY_ENABLED, "false"));
        scope = sysConfigService.get(KEY_SCOPE, "ALL");
        network = sysConfigService.get(KEY_NETWORK, "");
        cronExpression = sysConfigService.get(KEY_CRON, "0 0 2 * * ?");
        lastRunStatus = sysConfigService.get(KEY_LAST_RUN_STATUS, "NEVER");
        lastRunTime = sysConfigService.get(KEY_LAST_RUN_TIME, "");

        if (enabled) {
            scheduleNext();
        }
        log.info("定时巡检调度器初始化: enabled={}, cron={}, scope={}", enabled, cronExpression, scope);
    }

    private void scheduleNext() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = taskScheduler.schedule(this::scheduledInspection, new CronTrigger(cronExpression));
        log.info("定时巡检已调度: cron={}", cronExpression);
    }

    public void scheduledInspection() {
        if (!enabled) {
            return;
        }

        log.info("定时巡检触发: scope={}, network={}", scope, network);
        try {
            InspectionRound round;
            if ("NETWORK".equals(scope) && !network.isEmpty()) {
                round = inspectionService.triggerScheduledInspection("NETWORK", network);
            } else {
                round = inspectionService.triggerScheduledInspection("ALL", null);
            }
            lastRunStatus = "SUCCESS";
            lastRunTime = java.time.LocalDateTime.now().toString();
            persistRunStatus();
            log.info("定时巡检已提交: roundId={}", round.getId());
        } catch (IllegalStateException e) {
            lastRunStatus = "SKIPPED";
            lastRunTime = java.time.LocalDateTime.now().toString();
            persistRunStatus();
            log.warn("定时巡检跳过: {}", e.getMessage());
        } catch (Exception e) {
            lastRunStatus = "FAILED";
            lastRunTime = java.time.LocalDateTime.now().toString();
            persistRunStatus();
            log.error("定时巡检失败: {}", e.getMessage(), e);
        }
    }

    private void persistRunStatus() {
        sysConfigService.set(KEY_LAST_RUN_STATUS, lastRunStatus);
        sysConfigService.set(KEY_LAST_RUN_TIME, lastRunTime);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("scope", scope);
        status.put("network", network);
        status.put("cronExpression", cronExpression);
        status.put("lastRunStatus", lastRunStatus);
        status.put("lastRunTime", lastRunTime);
        return status;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        sysConfigService.set(KEY_ENABLED, String.valueOf(enabled));
        if (enabled) {
            scheduleNext();
        } else if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        log.info("定时巡检已{}", enabled ? "启用" : "禁用");
    }

    public void updateConfig(boolean enabled, String scope, String network, String cronExpression) {
        this.enabled = enabled;
        this.scope = scope;
        this.network = network != null ? network : "";
        this.cronExpression = cronExpression;

        sysConfigService.set(KEY_ENABLED, String.valueOf(enabled));
        sysConfigService.set(KEY_SCOPE, this.scope);
        sysConfigService.set(KEY_NETWORK, this.network);
        sysConfigService.set(KEY_CRON, this.cronExpression);

        if (enabled) {
            scheduleNext();
        } else if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        log.info("定时巡检配置已更新: enabled={}, scope={}, network={}, cron={}", enabled, scope, network, cronExpression);
    }
}
