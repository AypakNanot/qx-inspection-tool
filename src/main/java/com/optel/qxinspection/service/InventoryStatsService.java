package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.mysql.*;
import com.optel.qxinspection.repository.mysql.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 库存统计服务 - 从MySQL老库查询设备/盘/端口的静态统计数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryStatsService {

    private final DmeoRepository dmeoRepository;
    private final DmNeRepository dmNeRepository;
    private final DefDmNeRepository defDmNeRepository;
    private final DmRelationRepository dmRelationRepository;

    private static final int CID_NETWORK = 1;
    private static final int CID_NE = 2;
    private static final int CID_SLOT = 4;
    private static final int CID_PORT = 5;

    /**
     * 获取所有网络名列表（从 dmeo cid=1）
     */
    public List<String> getNetworkNames() {
        return dmeoRepository.findByCid(CID_NETWORK).stream()
                .map(Dmeo::getName)
                .filter(n -> n != null && !n.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 总览统计
     */
    public Map<String, Object> getOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        long neCount = dmNeRepository.count();
        long slotCount = dmeoRepository.countGroupByNePrefix(CID_SLOT).stream()
                .mapToLong(row -> (long) row[1]).sum();
        long portCount = dmeoRepository.countGroupByNePrefix(CID_PORT).stream()
                .mapToLong(row -> (long) row[1]).sum();
        long networkCount = dmeoRepository.countByCid(CID_NETWORK);

        result.put("neCount", neCount);
        result.put("slotCount", slotCount);
        result.put("portCount", portCount);
        result.put("networkCount", networkCount);
        return result;
    }

    /**
     * 网元统计（按设备类型），支持按网络筛选
     */
    public Map<String, Object> getNeStats(String network) {
        Map<String, Object> result = new LinkedHashMap<>();

        List<DmNe> allNe = dmNeRepository.findAll();
        Map<String, String> neTypeMap = buildNeTypeMap();
        Map<String, String> neNetworkMap = buildNeNetworkMap();

        // 按网络筛选
        if (network != null && !network.isEmpty()) {
            allNe = allNe.stream()
                    .filter(ne -> network.equals(neNetworkMap.get(ne.getOid())))
                    .toList();
        }

        // 按设备类型分组
        Map<String, Long> byType = new LinkedHashMap<>();
        for (DmNe ne : allNe) {
            String typeName = neTypeMap.getOrDefault(ne.getOid(), "未知");
            byType.merge(typeName, 1L, Long::sum);
        }
        result.put("byNeTypeName", toSortedList(byType));

        return result;
    }

    /**
     * 获取盘/端口的类型列表（用于筛选下拉框）
     */
    public List<Integer> getObjectTypes(int cid) {
        return dmeoRepository.findByCid(cid).stream()
                .map(Dmeo::getType)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 盘统计（支持按网络和盘类型筛选）
     */
    public Map<String, Object> getSlotStats(String network, Integer objectType) {
        return getDmeoStats(CID_SLOT, network, objectType);
    }

    /**
     * 端口统计（支持按网络和端口类型筛选）
     */
    public Map<String, Object> getPortStats(String network, Integer objectType) {
        return getDmeoStats(CID_PORT, network, objectType);
    }

    private Map<String, Object> getDmeoStats(int cid, String network, Integer objectType) {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, String> neNetworkMap = buildNeNetworkMap();

        // 加载全部 dmeo 按 cid
        List<Dmeo> allDmeo = dmeoRepository.findByCid(cid);

        // 按对象类型分组
        Map<String, Long> byType = new LinkedHashMap<>();

        for (Dmeo d : allDmeo) {
            String neOid = extractNeOid(d.getOid());
            String networkName = neNetworkMap.getOrDefault(neOid, "未分配");

            // 按网络筛选
            if (network != null && !network.isEmpty() && !network.equals(networkName)) {
                continue;
            }
            // 按对象类型筛选
            if (objectType != null && !objectType.equals(d.getType())) {
                continue;
            }

            String typeName = d.getType() != null ? String.valueOf(d.getType()) : "未知";
            byType.merge(typeName, 1L, Long::sum);
        }

        result.put("byTypeName", toSortedList(byType));

        return result;
    }

    /**
     * 从 dmeo 的 oid 中提取 NE 的 oid 前缀
     * 盘/端口的 oid 格式如 "neOid:slotOid" 或 "neOid:slotOid:portOid"
     */
    private String extractNeOid(String dmeoOid) {
        if (dmeoOid == null) return "";
        int firstColon = dmeoOid.indexOf(':');
        if (firstColon < 0) return dmeoOid;
        return dmeoOid.substring(0, firstColon);
    }

    /**
     * 构建 neOid → 设备类型名 映射
     * neOid 来自 dmne.oid，设备类型来自 defdmne.cName
     */
    private Map<String, String> buildNeTypeMap() {
        List<DmNe> allNe = dmNeRepository.findAll();
        Map<Integer, String> typeDefMap = defDmNeRepository.findAll().stream()
                .collect(Collectors.toMap(DefDmNe::getNeType,
                        d -> d.getCName() != null ? d.getCName() : d.getEName(),
                        (a, b) -> a));

        Map<String, String> result = new HashMap<>();
        for (DmNe ne : allNe) {
            String typeName = ne.getType() != null
                    ? typeDefMap.getOrDefault(ne.getType(), "未知(" + ne.getType() + ")")
                    : "未知";
            result.put(ne.getOid(), typeName);
        }
        return result;
    }

    /**
     * 构建 netOid → 网络名 映射（从 dmeo cid=1）
     */
    private Map<String, String> buildNetNameMap() {
        Map<String, String> netNameMap = new HashMap<>();
        for (Dmeo net : dmeoRepository.findByCid(CID_NETWORK)) {
            netNameMap.put(net.getOid(), net.getName() != null ? net.getName() : net.getOid());
        }
        return netNameMap;
    }

    /**
     * 构建 neOid → 网络名 映射
     * 通过 dmrelation 表（type=1）获取 NE 归属网络，再从 dmeo cid=1 查网络名
     */
    private Map<String, String> buildNeNetworkMap() {
        Map<String, String> netNameMap = buildNetNameMap();
        Map<String, String> result = new HashMap<>();

        // 从 dmrelation 表获取 NE→网络的归属关系（type=1）
        List<DmRelation> relations = dmRelationRepository.findAll();
        for (DmRelation r : relations) {
            if (r.getType() != null && r.getType() == 1) {
                String networkName = netNameMap.getOrDefault(r.getReo(), r.getReo());
                result.put(r.getOid(), networkName);
            }
        }
        return result;
    }

    private List<Map<String, Object>> toSortedList(Map<String, Long> map) {
        List<Map<String, Object>> list = new ArrayList<>();
        map.forEach((key, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", key);
            entry.put("count", value);
            list.add(entry);
        });
        list.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));
        return list;
    }
}
