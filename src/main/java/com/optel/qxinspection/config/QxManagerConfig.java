package com.optel.qxinspection.config;

import com.optel.qx.cci.channel.QxChannelManager;
import com.optel.qx.cci.util.QxConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QxChannelManager 配置
 * 绕开 TCPChannel.initialize()，直接构造 QxChannelManager，不启 UDP 9910
 */
@Slf4j
@Configuration
public class QxManagerConfig {

    @Value("${app.qx.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${app.qx.login-timeout-sec:20}")
    private int loginTimeoutSec;

    @Value("${app.qx.heartbeat-interval-sec:60}")
    private int heartbeatIntervalSec;

    @Value("${app.qx.heartbeat-timeout-sec:180}")
    private int heartbeatTimeoutSec;

    @Value("${app.qx.business-threads:4}")
    private int businessThreads;

    private volatile QxChannelManager manager;

    @Bean
    public QxChannelManager qxChannelManager() {
        QxConfig config = QxConfig.defaults()
                .setConnectTimeoutMs(connectTimeoutMs)
                .setLoginTimeoutSec(loginTimeoutSec)
                .setHeartbeatIntervalSec(heartbeatIntervalSec)
                .setHeartbeatTimeoutSec(heartbeatTimeoutSec)
                .setBusinessThreads(businessThreads)
                .setTrapThreads(1)
                .setStatsIntervalSec(3600) // 统计日志间隔设为1小时（SDK不支持0）
                .setUdpDyingGaspPort(0);   // 不启用 UDP 监听

        manager = new QxChannelManager(config);
        manager.start();
        log.info("QxChannelManager 已启动 (connectTimeout={}ms, heartbeat={}s)",
                connectTimeoutMs, heartbeatIntervalSec);
        return manager;
    }

    @PreDestroy
    public void destroy() {
        if (manager != null && manager.isStarted()) {
            log.info("正在关闭 QxChannelManager...");
            manager.stop();
            log.info("QxChannelManager 已关闭");
        }
    }
}
