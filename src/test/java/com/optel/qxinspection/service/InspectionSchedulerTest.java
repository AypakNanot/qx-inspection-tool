package com.optel.qxinspection.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 定时巡检调度器测试。
 * <p>重点：非法 cron 绝不能落库——一旦写进 sys_config，下次启动读出来会让应用起不来。</p>
 */
@ExtendWith(MockitoExtension.class)
class InspectionSchedulerTest {

    private static final String KEY_ENABLED = "schedule.enabled";
    private static final String KEY_CRON = "schedule.cron";
    private static final String KEY_SCOPE = "schedule.scope";
    private static final String KEY_NETWORK = "schedule.network";
    private static final String KEY_LAST_RUN_STATUS = "schedule.lastRunStatus";
    private static final String KEY_LAST_RUN_TIME = "schedule.lastRunTime";

    @Mock
    private InspectionService inspectionService;

    @Mock
    private SysConfigService sysConfigService;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ScheduledFuture<Object> scheduledFuture;

    @InjectMocks
    private InspectionScheduler scheduler;

    @Test
    void updateConfig_validSixFieldCron_persistsAndSchedules() {
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        scheduler.updateConfig(true, "NETWORK", "上饶南区_地调", "0 30 3 * * ?");

        assertEquals("0 30 3 * * ?", scheduler.getStatus().get("cronExpression"));
        assertEquals(true, scheduler.getStatus().get("enabled"));
        assertEquals("NETWORK", scheduler.getStatus().get("scope"));
        verify(sysConfigService).set(KEY_CRON, "0 30 3 * * ?");
        verify(sysConfigService).set(KEY_ENABLED, "true");
    }

    @Test
    void updateConfig_fiveFieldCron_rejectedBeforeAnythingIsPersisted() {
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.updateConfig(true, "ALL", "", "0 0 2 * *"));

        verify(sysConfigService, never()).set(eq(KEY_CRON), anyString());
        verify(sysConfigService, never()).set(eq(KEY_ENABLED), anyString());
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void updateConfig_blankOrNullCron_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.updateConfig(false, "ALL", "", "   "));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.updateConfig(false, "ALL", "", null));

        verify(sysConfigService, never()).set(eq(KEY_CRON), anyString());
    }

    @Test
    void updateConfig_invalidCron_leavesPreviousExpressionUntouched() {
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
        scheduler.updateConfig(true, "ALL", "", "0 0 5 * * ?");

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.updateConfig(true, "ALL", "", "not-a-cron"));

        assertEquals("0 0 5 * * ?", scheduler.getStatus().get("cronExpression"));
        verify(sysConfigService, never()).set(KEY_CRON, "not-a-cron");
    }

    @Test
    void afterPropertiesSet_invalidStoredCron_disablesInsteadOfFailingStartup() {
        stubStoredConfig("true", "0 0 2 * *");

        assertDoesNotThrow(() -> scheduler.afterPropertiesSet());

        assertEquals(false, scheduler.getStatus().get("enabled"), "非法存储值应降级为禁用");
        assertEquals(InspectionScheduler.DEFAULT_CRON, scheduler.getStatus().get("cronExpression"));
        verify(sysConfigService).set(KEY_ENABLED, "false");
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void afterPropertiesSet_validStoredCron_schedulesAndTrimsWhitespace() {
        stubStoredConfig("true", " 0 15 4 * * ? ");
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));

        scheduler.afterPropertiesSet();

        assertEquals(true, scheduler.getStatus().get("enabled"));
        assertEquals("0 15 4 * * ?", scheduler.getStatus().get("cronExpression"));
        verify(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    /** afterPropertiesSet 会读取全部配置键，需一次性 stub 齐，否则严格模式会判定参数不匹配 */
    private void stubStoredConfig(String enabled, String cron) {
        when(sysConfigService.get(KEY_ENABLED, "false")).thenReturn(enabled);
        when(sysConfigService.get(KEY_SCOPE, "ALL")).thenReturn("ALL");
        when(sysConfigService.get(KEY_NETWORK, "")).thenReturn("");
        when(sysConfigService.get(KEY_LAST_RUN_STATUS, "NEVER")).thenReturn("NEVER");
        when(sysConfigService.get(KEY_LAST_RUN_TIME, "")).thenReturn("");
        when(sysConfigService.get(KEY_CRON, InspectionScheduler.DEFAULT_CRON)).thenReturn(cron);
    }

    @Test
    void setEnabled_false_cancelsPendingTask() {
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
        scheduler.setEnabled(true);

        scheduler.setEnabled(false);

        verify(scheduledFuture).cancel(false);
        verify(sysConfigService).set(KEY_ENABLED, "false");
    }

    @Test
    void scheduledInspection_disabled_doesNothing() {
        scheduler.scheduledInspection();

        verifyNoInteractions(inspectionService);
    }
}
