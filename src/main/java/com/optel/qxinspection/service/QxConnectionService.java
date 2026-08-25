package com.optel.qxinspection.service;

import com.optel.qx.cci.channel.*;
import com.optel.qx.cci.util.ChannelID;
import com.optel.qx.cci.util.ChannelProp;
import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.qx.QxDeviceServiceImpl;
import com.optel.qxinspection.reconnect.QxReconnectManager;
import com.optel.qxinspection.repository.sqlite.ConnProfileRepository;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class QxConnectionService {

    private final QxDeviceServiceImpl qxDeviceService;
    private final QxReconnectManager reconnectManager;
    private final ConnProfileRepository connProfileRepository;
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;

    // neOid → ChannelID 映射缓存
    private final ConcurrentHashMap<String, ChannelID> channelIdMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 注册状态监听器到 QxChannelManager
        qxDeviceService.getManager().addStateListener(this::onStateChanged);
        log.info("QxConnectionService initialized, state listener registered");
    }

    /**
     * 状态变化回调（在 Netty I/O 线程，必须轻量）
     */
    private void onStateChanged(ChannelID channelId, QxChannelState prev, QxChannelState current, String error) {
        String neOid = findNeOidByChannelId(channelId);
        if (neOid == null) return;

        log.debug("State change neOid={}, {} -> {}, error={}", neOid, prev, current, error);

        // 更新 SQLite 中的状态
        deviceAccessConfigRepository.findByNeId(neOid).ifPresent(config -> {
            if (current == QxChannelState.ONLINE) {
                config.setConnectionStatus(1);
                config.setLastConnectTime(LocalDateTime.now());
            } else if (current == QxChannelState.OFFLINE || current == QxChannelState.CLOSED) {
                config.setConnectionStatus(0);
            }
            deviceAccessConfigRepository.save(config);
        });

        // 离线时触发重连
        if (current == QxChannelState.OFFLINE && error != null) {
            ChannelProp prop = buildChannelProp(neOid);
            if (prop != null) {
                reconnectManager.onOffline(neOid, channelId, prop);
            }
        }
    }

    private String findNeOidByChannelId(ChannelID channelId) {
        for (var entry : channelIdMap.entrySet()) {
            if (entry.getValue().equals(channelId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 一键全部连接
     */
    public Map<String, Object> connectAll() {
        List<DeviceAccessConfig> devices = deviceAccessConfigRepository.findByEnabledTrue();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        List<String> failedDevices = new ArrayList<>();

        ConnProfile globalProfile = connProfileRepository.findByScopeAndNeOid("GLOBAL", "")
                .orElse(null);

        List<CompletableFuture<QxChannel>> futures = new ArrayList<>();

        for (DeviceAccessConfig device : devices) {
            int port = getPort(device, globalProfile);

            // 注册端点到 QxDeviceServiceImpl
            qxDeviceService.registerEndpoint(device.getNeId(), device.getIpAddr(), port,
                    device.getUsername(), device.getPassword());

            ChannelID channelId = new ChannelID(device.getIpAddr(), port);
            channelIdMap.put(device.getNeId(), channelId);

            ChannelProp prop = buildChannelProp(device.getNeId());
            if (prop == null) {
                fail.incrementAndGet();
                failedDevices.add(device.getNeId() + "(no config)");
                continue;
            }

            CompletableFuture<QxChannel> future = qxDeviceService.getManager().connect(channelId, prop);
            futures.add(future);

            future.whenComplete((ch, ex) -> {
                if (ex == null && ch != null) {
                    success.incrementAndGet();
                } else {
                    fail.incrementAndGet();
                    failedDevices.add(device.getNeId());
                }
            });
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Batch connect timed out");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", devices.size());
        result.put("success", success.get());
        result.put("fail", fail.get());
        result.put("failedDevices", failedDevices);
        return result;
    }

    /**
     * 一键断开
     */
    public Map<String, Object> disconnectAll() {
        Collection<QxChannel> channels = qxDeviceService.getManager().allChannels();
        int count = channels.size();

        for (QxChannel ch : new ArrayList<>(channels)) {
            try {
                qxDeviceService.getManager().shut(ch.getChannelId());
            } catch (Exception e) {
                log.warn("Failed to shut channel: {}", ch.getChannelId(), e);
            }
        }

        channelIdMap.keySet().forEach(reconnectManager::cancel);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("disconnected", count);
        return result;
    }

    /**
     * 单设备连接
     */
    public boolean connectSingle(String neOid) {
        DeviceAccessConfig config = deviceAccessConfigRepository.findByNeId(neOid).orElse(null);
        if (config == null) return false;

        ConnProfile globalProfile = connProfileRepository.findByScopeAndNeOid("GLOBAL", "").orElse(null);
        int port = getPort(config, globalProfile);

        // 注册端点到 QxDeviceServiceImpl（供生成的 Service 使用）
        qxDeviceService.registerEndpoint(neOid, config.getIpAddr(), port,
                config.getUsername(), config.getPassword());

        ChannelID channelId = new ChannelID(config.getIpAddr(), port);
        channelIdMap.put(neOid, channelId);

        ChannelProp prop = buildChannelProp(neOid);
        if (prop == null) return false;

        try {
            qxDeviceService.getManager().connect(channelId, prop)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.warn("connectSingle failed neOid={}: {}", neOid, e.getMessage());
            return false;
        }
    }

    /**
     * 检查设备是否在线
     */
    public boolean isConnected(String neOid) {
        ChannelID channelId = channelIdMap.get(neOid);
        if (channelId == null) return false;
        QxChannel ch = qxDeviceService.getManager().getRegistry().getIfPresent(channelId);
        return ch != null && ch.isOnline();
    }

    /**
     * 单设备断开
     */
    public boolean disconnectSingle(String neOid) {
        ChannelID channelId = channelIdMap.get(neOid);
        if (channelId == null) return false;

        reconnectManager.cancel(neOid);
        try {
            qxDeviceService.getManager().shut(channelId);
            return true;
        } catch (Exception e) {
            log.warn("disconnectSingle failed neOid={}: {}", neOid, e.getMessage());
            return false;
        }
    }

    /**
     * 获取所有设备连接状态
     */
    public List<Map<String, Object>> getConnectionStatus() {
        List<DeviceAccessConfig> devices = deviceAccessConfigRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (DeviceAccessConfig device : devices) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("neId", device.getNeId());
            item.put("neName", device.getNeName());
            item.put("neTypeName", device.getNeTypeName());
            item.put("networkName", device.getNetworkName());
            item.put("ipAddr", device.getIpAddr());
            item.put("connectionStatus", device.getConnectionStatus());

            ChannelID channelId = channelIdMap.get(device.getNeId());
            if (channelId != null) {
                QxChannel ch = qxDeviceService.getManager().getRegistry().getIfPresent(channelId);
                if (ch != null) {
                    item.put("sdkState", ch.getState().name());
                    item.put("online", ch.isOnline());
                }
            }

            result.add(item);
        }
        return result;
    }

    /**
     * 获取连接统计摘要
     */
    public Map<String, Object> getConnectionSummary() {
        List<DeviceAccessConfig> devices = deviceAccessConfigRepository.findAll();
        long online = devices.stream().filter(d -> d.getConnectionStatus() == 1).count();
        long total = devices.size();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("online", online);
        summary.put("offline", total - online);
        summary.put("total", total);
        summary.put("sdkChannels", qxDeviceService.getManager().allChannels().size());
        return summary;
    }

    /**
     * 保存全局连接配置
     */
    public ConnProfile saveGlobalConfig(String username, String password, int port) {
        ConnProfile profile = connProfileRepository.findByScopeAndNeOid("GLOBAL", "")
                .orElse(new ConnProfile());
        profile.setScope("GLOBAL");
        profile.setNeOid("");
        profile.setUsername(username);
        profile.setPassword(password);
        profile.setPort(port);
        return connProfileRepository.save(profile);
    }

    /**
     * 获取全局连接配置
     */
    public Optional<ConnProfile> getGlobalConfig() {
        return connProfileRepository.findByScopeAndNeOid("GLOBAL", "");
    }

    /**
     * 保存单设备连接配置
     */
    public ConnProfile saveDeviceConfig(String neOid, String username, String password, Integer port) {
        ConnProfile profile = connProfileRepository.findByScopeAndNeOid("NE", neOid)
                .orElse(new ConnProfile());
        profile.setScope("NE");
        profile.setNeOid(neOid);
        profile.setUsername(username);
        profile.setPassword(password);
        if (port != null) profile.setPort(port);
        return connProfileRepository.save(profile);
    }

    /**
     * 获取单设备连接配置
     */
    public Optional<ConnProfile> getDeviceConfig(String neOid) {
        return connProfileRepository.findByScopeAndNeOid("NE", neOid);
    }

    /**
     * 删除单设备连接配置
     */
    public void deleteDeviceConfig(String neOid) {
        connProfileRepository.findByScopeAndNeOid("NE", neOid)
                .ifPresent(connProfileRepository::delete);
    }

    /**
     * 构建 ChannelProp（全局配置 + 单设备覆盖）
     */
    private ChannelProp buildChannelProp(String neOid) {
        Optional<ConnProfile> deviceProfile = connProfileRepository.findByScopeAndNeOid("NE", neOid);
        ConnProfile profile = deviceProfile.orElse(
                connProfileRepository.findByScopeAndNeOid("GLOBAL", "").orElse(null));

        if (profile == null) return null;

        ChannelID channelId = channelIdMap.get(neOid);
        if (channelId == null) {
            channelId = new ChannelID("0.0.0.0", profile.getPort());
        }

        return new ChannelProp(channelId, profile.getUsername(), profile.getPassword());
    }

    public int getEffectivePort(DeviceAccessConfig device) {
        ConnProfile globalProfile = connProfileRepository.findByScopeAndNeOid("GLOBAL", "").orElse(null);
        return getPort(device, globalProfile);
    }

    public int getPort(DeviceAccessConfig device, ConnProfile globalProfile) {
        Optional<ConnProfile> deviceProfile = connProfileRepository.findByScopeAndNeOid("NE", device.getNeId());
        if (deviceProfile.isPresent()) return deviceProfile.map(ConnProfile::getPort).orElse(9900);
        if (globalProfile != null) return globalProfile.getPort();
        return 9900;
    }
}
