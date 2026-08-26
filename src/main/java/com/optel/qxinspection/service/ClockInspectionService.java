package com.optel.qxinspection.service;

import com.optel.qxinspection.clock.ClockStateAckData;
import com.optel.qxinspection.clock.ClockStateGetData;
import com.optel.qxinspection.clock.ClockUnitStateAckData;
import com.optel.qxinspection.clock.ClockUnitStateGetData;
import com.optel.qxinspection.clock.service.IClockService;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 时钟拓扑采集服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClockInspectionService {

    private final IClockService clockService;
    private final QxConnectionService qxConnectionService;
    private final DeviceAccessConfigRepository deviceAccessConfigRepository;

    /** 缓存的时钟拓扑数据 */
    private volatile List<ClockNode> cachedTopology = Collections.emptyList();
    private volatile long lastRefreshTime = 0;

    /**
     * 获取全网时钟拓扑（优先使用缓存）
     */
    public List<ClockNode> getTopology() {
        return cachedTopology;
    }

    /**
     * 刷新时钟拓扑数据
     */
    public List<ClockNode> refreshTopology() {
        List<DeviceAccessConfig> devices = deviceAccessConfigRepository.findByEnabledTrue();
        List<ClockNode> nodes = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (DeviceAccessConfig device : devices) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    ClockNode node = queryClockInfo(device);
                    if (node != null) {
                        nodes.add(node);
                    }
                } catch (Exception e) {
                    log.debug("时钟查询失败: {}, error={}", device.getNeName(), e.getMessage());
                }
            }, pool));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("时钟拓扑采集超时");
        } finally {
            pool.shutdown();
        }

        // 按网元名排序
        nodes.sort(Comparator.comparing(ClockNode::getNeName));

        cachedTopology = nodes;
        lastRefreshTime = System.currentTimeMillis();
        log.info("时钟拓扑采集完成: {} 台网元", nodes.size());
        return nodes;
    }

    /**
     * 查询单个网元的时钟信息
     */
    private ClockNode queryClockInfo(DeviceAccessConfig device) {
        String neId = device.getNeId();

        // 确保设备已连接
        if (!qxConnectionService.isConnected(neId)) {
            boolean connected = qxConnectionService.connectSingle(neId);
            if (!connected) {
                return null;
            }
        }

        ClockNode node = new ClockNode();
        node.setNeId(neId);
        node.setNeName(device.getNeName());
        node.setNetworkName(device.getNetworkName());
        node.setIpAddr(device.getIpAddr());

        // 查询时钟模块状态 (0x241A)
        try {
            ClockUnitStateGetData unitReq = ClockUnitStateGetData.builder()
                    .subcaseNo(0).slotId(0).portType(0x11)
                    .portSubType(0x07).portId(1)
                    .backup1(0).backup2(0).build();
            ClockUnitStateAckData unitResp = clockService.unitStateGet(neId, unitReq);
            node.setClockState(unitResp.getClockState());
        } catch (Exception e) {
            log.debug("时钟模块状态查询失败: {}", neId);
            node.setClockState(0); // 未知
        }

        // 查询时钟源状态 (0x241B) - 所有可选时钟源
        List<ClockSource> sources = new ArrayList<>();
        try {
            ClockStateGetData stateReq = ClockStateGetData.builder()
                    .subcaseNo(0).slotId(0).portType(0)
                    .portSubType(0).portId(0)
                    .clockType(1).queryScope(1).build(); // 系统时钟, 所有可选
            ClockStateAckData stateResp = clockService.stateGet(neId, stateReq);
            sources.add(buildClockSource(stateResp, true));
        } catch (Exception e) {
            log.debug("系统时钟源查询失败: {}", neId);
        }

        // 查询导出时钟源
        try {
            ClockStateGetData stateReq = ClockStateGetData.builder()
                    .subcaseNo(0).slotId(0).portType(0)
                    .portSubType(0).portId(0)
                    .clockType(2).queryScope(1).build(); // 导出时钟, 所有可选
            ClockStateAckData stateResp = clockService.stateGet(neId, stateReq);
            sources.add(buildClockSource(stateResp, false));
        } catch (Exception e) {
            log.debug("导出时钟源查询失败: {}", neId);
        }

        node.setSources(sources);
        return node;
    }

    private ClockSource buildClockSource(ClockStateAckData resp, boolean isSystemClock) {
        ClockSource src = new ClockSource();
        src.setSlotId(resp.getSlotId());
        src.setPortId(resp.getPortId());
        src.setPriority(resp.getPriority());
        src.setClockSourceState(resp.getClockSourceState());
        src.setRealSSM(resp.getRealSSM());
        src.setSelReason(resp.getSelReason());
        src.setLockout(resp.getLockout() == 1);
        src.setAlter(resp.getAlter() == 1);
        src.setManul(resp.getManul() == 1);
        src.setSystemClock(isSystemClock);
        // 当前使用的时钟源：选择原因 > 0 且状态可用
        src.setCurrent(resp.getClockSourceState() == 1 && resp.getSelReason() > 0);
        return src;
    }

    // ==================== 数据结构 ====================

    @lombok.Data
    public static class ClockNode {
        private String neId;
        private String neName;
        private String networkName;
        private String ipAddr;
        /** 时钟状态: 0=未知, 1=锁定, 2=保持, 3=自由振荡 */
        private int clockState;
        private List<ClockSource> sources = new ArrayList<>();

        public String getClockStateText() {
            return switch (clockState) {
                case 1 -> "锁定";
                case 2 -> "保持";
                case 3 -> "自由振荡";
                default -> "未知";
            };
        }
    }

    @lombok.Data
    public static class ClockSource {
        private int slotId;
        private int portId;
        private int priority;
        /** 时钟源状态: 1=可用, 2=不可用 */
        private int clockSourceState;
        /** SSM 实际值 */
        private int realSSM;
        /** 选择原因码 */
        private int selReason;
        private boolean lockout;
        private boolean alter;
        private boolean manul;
        /** 是否系统时钟（vs 导出时钟） */
        private boolean systemClock;
        /** 是否当前使用的时钟源 */
        private boolean current;
    }
}
