package com.optel.qxinspection.service;

import com.optel.qxinspection.laser.LaserAttributeGetReq;
import com.optel.qxinspection.perf.PerfCurrent24HGetReq;
import com.optel.qxinspection.util.OidUtil;

/**
 * 要巡检的端口对象：报文坐标（子架/槽位/端口号）与端口类型（portType/portSubType）
 * 在装载阶段一次解析、不可变携带；采集时直接从对象取值并组装请求，不再反复查 Map、解析 OID。
 *
 * <p>类型来源：dmeo.deviceType（同步时从 defobject 冗余写入）高低位拆分，
 * 缺失/非法兜底 0xFF/0xFF（见 {@link #fallback}）。</p>
 *
 * @param oid        端口 OID（如 "101:1:11:2:1"），samples 组装与进度显示的键
 * @param subcaseNo  子架序号
 * @param slotId     槽位序号
 * @param portId     物理端口号
 * @param portType   端口类型（deviceType 高字节）
 * @param portSubType 端口子类型（deviceType 低字节）
 */
record PortTarget(String oid, int subcaseNo, int slotId, int portId, int portType, int portSubType) {

    /** 0x0C07 选择器：三字段 0xFFFF = 全部时隙 + 全部性能码，单条请求全查 */
    private static final int PERF_SELECT_ALL = 0xFFFF;
    /** 类型兜底值（未同步/脏数据时的占位编码） */
    private static final int TYPE_FALLBACK = 0xFF;

    /** 指定类型建对象，坐标从 OID 解析（OidUtil 防御式，永不抛异常） */
    static PortTarget of(String oid, int portType, int portSubType) {
        return new PortTarget(oid,
                OidUtil.getSubrackId(oid), OidUtil.getSlotId(oid), OidUtil.getPortId(oid),
                portType, portSubType);
    }

    /** 无映射端口兜底：坐标仍从 OID 解析，类型 0xFF/0xFF */
    static PortTarget fallback(String oid) {
        return of(oid, TYPE_FALLBACK, TYPE_FALLBACK);
    }

    /** 按 deviceType 高低位拆分建对象：高字节 portType、低字节 portSubType（如 515=0x0203 → 2/3） */
    static PortTarget fromDeviceType(String oid, int deviceType) {
        return of(oid, (deviceType >> 8) & 0xFF, deviceType & 0xFF);
    }

    /** 激光器查询请求（0x0C01） */
    LaserAttributeGetReq laserRequest() {
        return LaserAttributeGetReq.builder()
                .subcaseNo(subcaseNo)
                .slotId(slotId)
                .portType(portType)
                .portSubType(portSubType)
                .portId(portId)
                .backup(0)
                .build();
    }

    /** 性能查询请求（0x0C07）：三个选择器字段 0xFFFF，单条查全部性能 */
    PerfCurrent24HGetReq perfRequest() {
        return PerfCurrent24HGetReq.builder()
                .subcaseNo(subcaseNo)
                .slotId(slotId)
                .portType(portType)
                .portSubType(portSubType)
                .portId(portId)
                .tsOrderId(PERF_SELECT_ALL)
                .tsAttribute(PERF_SELECT_ALL)
                .performanceCode(PERF_SELECT_ALL)
                .build();
    }
}
