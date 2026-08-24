package com.optel.qxinspection.reconnect;

import com.optel.qx.cci.channel.QxChannelManager;
import com.optel.qx.cci.util.ChannelID;
import com.optel.qx.cci.util.ChannelProp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 断线重连管理器
 * 指数退避 + 抖动 + 熔断 + 全局并发信号量
 * 参考 ext-qx 的 QxReconnectManager 模式
 */
@Slf4j
@Component
public class QxReconnectManager {

    private final QxChannelManager manager;
    private final int baseSec;
    private final int maxSec;
    private final int circuitThreshold;
    private final int circuitCooldownSec;
    private final Semaphore globalSlots;
    private final ConcurrentHashMap<String, BackoffState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workerPool;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public QxReconnectManager(
            QxChannelManager manager,
            @Value("${app.qx.reconnect.base-sec:5}") int baseSec,
            @Value("${app.qx.reconnect.max-sec:300}") int maxSec,
            @Value("${app.qx.reconnect.max-concurrent:10}") int maxConcurrent,
            @Value("${app.qx.reconnect.circuit-threshold:5}") int circuitThreshold,
            @Value("${app.qx.reconnect.circuit-cooldown-sec:300}") int circuitCooldownSec) {
        this.manager = manager;
        this.baseSec = baseSec;
        this.maxSec = maxSec;
        this.circuitThreshold = circuitThreshold;
        this.circuitCooldownSec = circuitCooldownSec;
        this.globalSlots = new Semaphore(maxConcurrent);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qx-reconnect-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.workerPool = Executors.newFixedThreadPool(Math.max(2, maxConcurrent), r -> {
            Thread t = new Thread(r, "qx-reconnect-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 设备离线时调用，决定是否调度重连
     */
    public void onOffline(String neOid, ChannelID channelId, ChannelProp channelProp) {
        if (shutdown.get() || baseSec <= 0) return;

        BackoffState st = states.computeIfAbsent(neOid, k -> new BackoffState());

        // 熔断中，忽略
        if (st.isInCircuitBreak()) {
            return;
        }

        // 重置失败计数，从 0 开始调度
        st.failures = 0;
        scheduleAttempt(st, neOid, channelId, channelProp, 0);
    }

    /**
     * 取消指定设备的重连
     */
    public void cancel(String neOid) {
        BackoffState st = states.remove(neOid);
        if (st != null && st.future != null) {
            st.future.cancel(false);
        }
    }

    /**
     * 是否有正在进行的重连尝试
     */
    public boolean isAttemptInFlight(String neOid) {
        BackoffState st = states.get(neOid);
        return st != null && st.inFlight.get();
    }

    private void scheduleAttempt(BackoffState st, String neOid, ChannelID channelId,
                                  ChannelProp channelProp, int attempt) {
        long exp = Math.min(attempt, 20);
        long delaySec = Math.min(maxSec, baseSec * (1L << exp));
        long jitter = baseSec > 0 ? ThreadLocalRandom.current().nextLong(baseSec) : 0;
        long actualDelay = delaySec + jitter;

        log.debug("调度重连 neOid={}, attempt={}, delay={}s", neOid, attempt, actualDelay);

        // 先检查 st 是否仍是活跃的
        if (states.get(neOid) != st) return;

        st.future = scheduler.schedule(
                () -> attemptConnect(st, neOid, channelId, channelProp, attempt),
                actualDelay, TimeUnit.SECONDS);
    }

    private void attemptConnect(BackoffState st, String neOid, ChannelID channelId,
                                 ChannelProp channelProp, int attempt) {
        if (shutdown.get() || states.get(neOid) != st) return;

        boolean acquired = false;
        try {
            acquired = globalSlots.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!acquired) {
            log.debug("重连信号量忙，1s后重试 neOid={}", neOid);
            st.future = scheduler.schedule(
                    () -> attemptConnect(st, neOid, channelId, channelProp, attempt),
                    1, TimeUnit.SECONDS);
            return;
        }

        st.inFlight.set(true);
        try {
            log.info("尝试重连 neOid={}, attempt={}", neOid, attempt);
            manager.connect(channelId, channelProp).get(connectTimeoutSec(), TimeUnit.SECONDS);
            log.info("重连成功 neOid={}", neOid);
            states.remove(neOid);
        } catch (Exception e) {
            log.warn("重连失败 neOid={}, attempt={}, reason={}", neOid, attempt, e.getMessage());
            handleFailure(st, neOid, channelId, channelProp, attempt);
        } finally {
            st.inFlight.set(false);
            globalSlots.release();
        }
    }

    private void handleFailure(BackoffState st, String neOid, ChannelID channelId,
                                ChannelProp channelProp, int attempt) {
        st.failures++;

        if (st.failures >= circuitThreshold) {
            // 熔断
            st.circuitUntil = System.currentTimeMillis() + circuitCooldownSec * 1000L;
            log.warn("设备 neOid={} 连续{}次失败，熔断{}秒", neOid, st.failures, circuitCooldownSec);
            // 冷却后探测
            scheduleAttempt(st, neOid, channelId, channelProp, 0);
        } else {
            scheduleAttempt(st, neOid, channelId, channelProp, attempt + 1);
        }
    }

    private int connectTimeoutSec() {
        return 30; // 连接+登录总超时
    }

    @PreDestroy
    public void shutdown() {
        shutdown.set(true);
        scheduler.shutdownNow();
        workerPool.shutdownNow();
        states.clear();
    }

    private static class BackoffState {
        volatile int failures = 0;
        volatile long circuitUntil = 0;
        volatile ScheduledFuture<?> future;
        final AtomicBoolean inFlight = new AtomicBoolean(false);

        boolean isInCircuitBreak() {
            return System.currentTimeMillis() < circuitUntil;
        }
    }
}
