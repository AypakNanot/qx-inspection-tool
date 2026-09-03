package com.optel.qxinspection.qx;

import com.optel.dc.ext.qx.service.IQxDeviceService;
import com.optel.dc.ext.qx.service.impl.QxSendResult;
import com.optel.qx.cci.channel.QxChannel;
import com.optel.qx.cci.channel.QxChannelManager;
import com.optel.qx.cci.codec.Message;
import com.optel.qx.cci.codec.MsgHead;
import com.optel.qx.cci.facade.TCPChannel;
import com.optel.qx.cci.util.ChannelID;
import com.optel.qx.cci.util.ChannelProp;
import com.optel.qx.cci.util.QxConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集中式 Qx 设备通信服务。
 *
 * <p>管理所有设备端点注册、连接和命令发送。
 * 生成的 Service（LaserServiceImpl、DeviceServiceImpl）通过
 * {@link IQxDeviceService} 接口调用本类的 {@link #send} 方法。</p>
 */
@Slf4j
@Service
public class QxDeviceServiceImpl implements IQxDeviceService {

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

    @Value("${app.qx.default-port:9900}")
    private int defaultPort;

    /** neId -> Endpoint 映射 */
    private final ConcurrentHashMap<String, Endpoint> endpoints = new ConcurrentHashMap<>();

    private volatile QxChannelManager manager;

    @PostConstruct
    public void init() {
        QxConfig config = QxConfig.defaults()
                .setConnectTimeoutMs(connectTimeoutMs)
                .setLoginTimeoutSec(loginTimeoutSec)
                .setHeartbeatIntervalSec(heartbeatIntervalSec)
                .setHeartbeatTimeoutSec(heartbeatTimeoutSec)
                .setBusinessThreads(businessThreads)
                .setTrapThreads(1)
                .setStatsIntervalSec(3600)
                .setUdpDyingGaspPort(0);

        manager = new QxChannelManager(config);
        manager.start();
        log.info("QxDeviceService started (connectTimeout={}ms, heartbeat={}s)",
                connectTimeoutMs, heartbeatIntervalSec);
    }

    @PreDestroy
    public void shutdown() {
        if (manager != null && manager.isStarted()) {
            log.info("Shutting down QxChannelManager...");
            manager.stop();
            log.info("QxDeviceService stopped");
        }
    }

    // ==================== 端点管理 ====================

    /**
     * 注册设备端点
     */
    public void registerEndpoint(String neId, String host, int port, String user, String password) {
        endpoints.put(neId, new Endpoint(neId, host, port, user, password));
        log.info("Endpoint registered: neId={}, host={}:{}", neId, host, port);
    }

    /**
     * 注册设备端点（使用默认端口）
     */
    public void registerEndpoint(String neId, String host, String user, String password) {
        registerEndpoint(neId, host, defaultPort, user, password);
    }

    /**
     * 移除设备端点
     */
    public void unregisterEndpoint(String neId) {
        Endpoint ep = endpoints.remove(neId);
        if (ep != null && manager != null && manager.isStarted()) {
            try {
                manager.shut(channelId(ep));
            } catch (Exception e) {
                log.warn("Failed to shut channel: neId={}", neId, e);
            }
        }
        log.info("Endpoint unregistered: neId={}", neId);
    }

    /**
     * 连接指定设备
     */
    public boolean connect(String neId) {
        Endpoint ep = endpoints.get(neId);
        if (ep == null) {
            log.warn("connect: unknown neId={}", neId);
            return false;
        }
        try {
            QxChannel ch = manager.connect(channelId(ep), channelProp(ep))
                    .get(connectTimeoutMs + loginTimeoutSec * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            boolean ok = ch != null && ch.isOnline();
            log.info("connect: neId={}, result={}", neId, ok ? "ONLINE" : "FAILED");
            return ok;
        } catch (Exception e) {
            log.warn("connect failed: neId={}, err={}", neId, e.getMessage());
            return false;
        }
    }

    /**
     * 断开指定设备
     */
    public void disconnect(String neId) {
        Endpoint ep = endpoints.get(neId);
        if (ep == null) return;
        try {
            manager.shut(channelId(ep));
            log.info("disconnect: neId={}", neId);
        } catch (Exception e) {
            log.warn("disconnect failed: neId={}", neId, e);
        }
    }

    /**
     * 检查设备是否在线
     */
    public boolean isOnline(String neId) {
        Endpoint ep = endpoints.get(neId);
        if (ep == null || manager == null || !manager.isStarted()) return false;
        QxChannel ch = manager.getRegistry().getIfPresent(channelId(ep));
        return ch != null && ch.isOnline();
    }

    /**
     * 获取底层 QxChannelManager（供 QxConnectionService 使用）
     */
    public QxChannelManager getManager() {
        return manager;
    }

    /**
     * 获取已注册的端点
     */
    public Endpoint getEndpoint(String neId) {
        return endpoints.get(neId);
    }

    // ==================== IQxDeviceService ====================

    @Override
    public QxSendResult send(String neId, int cmdCode, byte[] payload, Integer timeoutMs) {
        long start = System.currentTimeMillis();
        Endpoint ep = endpoints.get(neId);
        if (ep == null) {
            return QxSendResult.fail(-400, "Unknown NE: " + neId, elapsed(start));
        }

        int timeoutSec = timeoutMs == null ? 10 : Math.max(1, (timeoutMs + 999) / 1000);
        ChannelProp prop = channelProp(ep);

        try {
            byte[] response = manager.send(
                    channelId(ep),
                    ep.user.getBytes(StandardCharsets.US_ASCII),
                    ep.password.getBytes(StandardCharsets.US_ASCII),
                    payload == null ? new byte[0] : payload,
                    (short) cmdCode,
                    timeoutSec);
            long elapsed = elapsed(start);

            if (response != null && response.length > MsgHead.HEAD_BYTE_LEN) {
                Message msg = Message.wrap(response);
                byte[] header = new byte[MsgHead.HEAD_BYTE_LEN];
                System.arraycopy(response, 0, header, 0, MsgHead.HEAD_BYTE_LEN);
                if (msg.getMsgHead().getResult() == 0) {
                    return QxSendResult.ok(msg.getPayload(), header, elapsed);
                }
                return QxSendResult.fail(msg.getMsgHead().getResult(),
                        "Device error: 0x" + Integer.toHexString(msg.getMsgHead().getResult()),
                        elapsed, msg.getPayload(), header);
            }
            return QxSendResult.ok(new byte[0], null, elapsed);
        } catch (Exception e) {
            log.error("send failed: neId={}, cmdCode=0x{}, err={}",
                    neId, String.format("%04X", cmdCode & 0xFFFF), e.getMessage());
            return QxSendResult.fail(-500, messageOf(e), elapsed(start));
        }
    }

    @Override
    public boolean shouldReconnect(String neId) {
        return endpoints.containsKey(neId);
    }

    // ==================== 内部方法 ====================

    private ChannelID channelId(Endpoint ep) {
        return new ChannelID(ep.host, ep.port, ep.neId);
    }

    private ChannelProp channelProp(Endpoint ep) {
        return new ChannelProp(channelId(ep), ep.user, ep.password);
    }

    private String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * 设备端点信息
     */
    public static class Endpoint {
        private final String neId;
        private final String host;
        private final int port;
        private final String user;
        private final String password;

        public Endpoint(String neId, String host, int port, String user, String password) {
            this.neId = neId;
            this.host = host;
            this.port = port;
            this.user = user != null ? user : "";
            this.password = password != null ? password : "";
        }

        public String getNeId() { return neId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getUser() { return user; }
        public String getPassword() { return password; }
    }
}
