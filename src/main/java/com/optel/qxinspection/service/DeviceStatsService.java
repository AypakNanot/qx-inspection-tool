package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceStatsService {

    private final DeviceAccessConfigRepository deviceAccessConfigRepository;

    /**
     * 按设备类型统计（库存口径：库里有几台）
     */
    public List<Map<String, Object>> statsByType() {
        return statsByType(null);
    }

    /**
     * 按设备类型统计，支持按网络筛选
     */
    public List<Map<String, Object>> statsByType(String networkName) {
        List<DeviceAccessConfig> devices = deviceAccessConfigRepository.findAll();
        if (networkName != null && !networkName.isEmpty()) {
            devices = devices.stream()
                    .filter(d -> networkName.equals(d.getNetworkName()))
                    .toList();
        }
        return buildStats(devices);
    }

    /**
     * 按设备类型统计（在线口径：当前连通几台）
     */
    public List<Map<String, Object>> statsByTypeOnline() {
        return statsByTypeOnline(null);
    }

    public List<Map<String, Object>> statsByTypeOnline(String networkName) {
        List<DeviceAccessConfig> devices = deviceAccessConfigRepository.findByConnectionStatus(1);
        if (networkName != null && !networkName.isEmpty()) {
            devices = devices.stream()
                    .filter(d -> networkName.equals(d.getNetworkName()))
                    .toList();
        }
        return buildStats(devices);
    }

    /**
     * 获取所有网络名称列表（用于筛选下拉）
     */
    public List<String> getNetworkNames() {
        return deviceAccessConfigRepository.findAll().stream()
                .map(DeviceAccessConfig::getNetworkName)
                .filter(n -> n != null && !n.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private List<Map<String, Object>> buildStats(List<DeviceAccessConfig> devices) {
        long total = devices.size();

        Map<String, Long> countMap = devices.stream()
                .collect(Collectors.groupingBy(
                        d -> d.getNeTypeName() != null ? d.getNeTypeName() : "Unknown",
                        Collectors.counting()));

        List<Map<String, Object>> result = new ArrayList<>();
        countMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("typeName", entry.getKey());
                    item.put("count", entry.getValue());
                    item.put("percent", total > 0 ? Math.round(entry.getValue() * 1000.0 / total) / 10.0 : 0);
                    result.add(item);
                });

        return result;
    }
}
