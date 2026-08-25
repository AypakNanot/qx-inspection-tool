package com.optel.qxinspection.util;

import java.util.*;

/**
 * OID 工具类 —— 冒号分割的层级对象标识解析。
 *
 * <p>OID 格式: {@code seg1:seg2:seg3:...}，每段为数字。
 * 层级关系: Network → NE → Subrack → Card/Slot → Port → Channel</p>
 *
 * <pre>
 * cid=1  Network   "1"
 * cid=2  NE        "101"
 * cid=3  Subrack   "101:1"
 * cid=4  Card/Slot "101:1:11"
 * cid=5  Port      "101:1:11:2"
 * cid=6  Channel   "101:1:11:2:1"
 * </pre>
 */
public final class OidUtil {

    private OidUtil() {}

    /** TS location port type */
    public static final int TS_PORT_TYPE = 0x51;
    /** VC4 cross-connect header port type */
    public static final int VC4_PORT_TYPE = 0x77;

    // ==================== 基础解析 ====================

    /**
     * 将冒号分隔的 OID 字符串解析为 int 数组。
     */
    public static int[] parseSegments(String oid) {
        if (oid == null || oid.isEmpty()) {
            return new int[0];
        }
        String[] parts = oid.split(":");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i]);
        }
        return result;
    }

    /**
     * 将 int 数组转回冒号分隔的 OID 字符串。
     */
    public static String toOid(int[] segments) {
        if (segments == null || segments.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(segments[i]);
        }
        return sb.toString();
    }

    // ==================== 层级提取 ====================
    // OID 格式: neId:subrack:slot:port:timeslot
    //   segment[0] = 网元ID (neId)
    //   segment[1] = 子架 (subrack)       → bSubCaseNo
    //   segment[2] = 槽位 (slot)          → bSlotID
    //   segment[3] = 端口 (port)          → wPortID
    //   segment[4] = 时隙 (timeslot)

    /**
     * 获取网元 OID（第一段）。
     * <p>例: "101:1:11:2:1" → "101"</p>
     */
    public static String getNeOid(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length >= 1 ? String.valueOf(seg[0]) : "";
    }

    /**
     * 获取网元+子架 OID（前两段）。
     * <p>例: "101:1:11:2:1" → "101:1"</p>
     */
    public static String getSubrackOid(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length >= 2 ? seg[0] + ":" + seg[1] : getNeOid(oid);
    }

    /**
     * 获取网元+子架+槽位 OID（前三段）。
     * <p>例: "101:1:11:2:1" → "101:1:11"</p>
     */
    public static String getSlotOid(String oid) {
        int[] seg = parseSegments(oid);
        if (seg.length >= 3) return seg[0] + ":" + seg[1] + ":" + seg[2];
        return getSubrackOid(oid);
    }

    /**
     * 获取网元+子架+槽位+端口 OID（前四段）。
     * <p>例: "101:1:11:2:1" → "101:1:11:2"</p>
     */
    public static String getPortOid(String oid) {
        int[] seg = parseSegments(oid);
        if (seg.length >= 4) return seg[0] + ":" + seg[1] + ":" + seg[2] + ":" + seg[3];
        return getSlotOid(oid);
    }

    /**
     * 获取父对象 OID（去掉最后一段）。
     * <p>例: "101:1:11:2" → "101:1:11"</p>
     */
    public static String getParentOid(String oid) {
        int lastColon = oid.lastIndexOf(':');
        return lastColon > 0 ? oid.substring(0, lastColon) : oid;
    }

    /**
     * 获取最后一段。
     * <p>例: "101:1:11" → 11</p>
     */
    public static int getEndSegment(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length > 0 ? seg[seg.length - 1] : 0;
    }

    // ==================== Qx 协议参数提取 ====================

    /**
     * 从 OID 提取网元ID（第一段）。
     * <p>例: "101:1:11:2:1" → 101</p>
     */
    public static int getNeId(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length >= 1 ? seg[0] : 0;
    }

    /**
     * 从 OID 提取子架号（第二段），对应协议 bSubCaseNo。
     * <p>例: "101:1:11:2:1" → 1</p>
     */
    public static int getSubrackId(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length >= 2 ? seg[1] : 1;
    }

    /**
     * 从 OID 提取槽位号（第三段），对应协议 bSlotID。
     * <p>例: "101:1:11:2:1" → 11</p>
     */
    public static int getSlotId(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length >= 3 ? seg[2] : 0;
    }

    /**
     * 从 OID 提取端口号（第四段），对应协议 wPortID。
     * <p>例: "101:1:11:2:1" → 2</p>
     */
    public static int getPortId(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length >= 4 ? seg[3] : 0;
    }

    /**
     * 从 OID 提取时隙（第五段）。
     * <p>例: "101:1:11:2:1" → 1</p>
     */
    public static int getTimeslot(String oid) {
        int[] seg = parseSegments(oid);
        return seg.length >= 5 ? seg[4] : 0;
    }

    /**
     * 从 TS_Location 端口 OID 提取 portSubType。
     * <p>OID 最后三段为 {@code slotNum:byteNum:portId}，
     * portSubType = (byteNum &lt;&lt; 5) | (slotNum &amp; 0x1F)</p>
     * <p>例: "101:1:11:2:1" → slotNum=11, byteNum=2 → portSubType=0x4B</p>
     */
    public static int getTsPortSubType(String oid) {
        int[] c = parseSegments(oid);
        if (c.length < 3) {
            throw new IllegalArgumentException("TS OID must have >= 3 segments: " + oid);
        }
        int slotNum = c[c.length - 3];
        int byteNum = c[c.length - 2];
        return ((byteNum & 0x07) << 5) | (slotNum & 0x1F);
    }

    /**
     * 从 portSubType 解码 byteNum（高 3 位）。
     */
    public static int byteFromPortSubType(int portSubType) {
        return (portSubType >> 5) & 0x07;
    }

    /**
     * 从 portSubType 解码 slotNum（低 5 位）。
     */
    public static int slotFromPortSubType(int portSubType) {
        return portSubType & 0x1F;
    }

    /**
     * 编码 portSubType = (byteNum &lt;&lt; 5) | (slotNum &amp; 0x1F)。
     */
    public static int encodePortSubType(int byteNum, int slotNum) {
        return ((byteNum & 0x07) << 5) | (slotNum & 0x1F);
    }

    /**
     * 从 TS_Location 的 portSubType + portId 反推端口 OID。
     * <p>例: neOid="101:1", portSubType=0x4B, portId=1 → "101:1:11:2:1"</p>
     */
    public static String tsToOid(String neOid, int portSubType, int portId) {
        int byteNum = byteFromPortSubType(portSubType);
        int slotNum = slotFromPortSubType(portSubType);
        return neOid + ":" + slotNum + ":" + byteNum + ":" + portId;
    }

    // ==================== 判断方法 ====================

    /**
     * 判断 target 是否是 source 的子节点（或相等）。
     * <p>例: isChildOf("1:2:3", "1:2") → true</p>
     */
    public static boolean isChildOf(String target, String source) {
        if (target == null || source == null) return false;
        return target.equals(source) || target.startsWith(source + ":");
    }

    /**
     * 判断是否为网元级别 OID（仅一段）。
     */
    public static boolean isNeLevel(String oid) {
        return parseSegments(oid).length == 1;
    }

    /**
     * 判断是否为端口级别 OID（至少四段：neId:subrack:slot:port）。
     */
    public static boolean isPortLevel(String oid) {
        return parseSegments(oid).length >= 4;
    }

    // ==================== 分组 ====================

    /**
     * 将 OID 列表按网元分组。
     */
    public static Map<String, List<String>> groupByNe(List<String> oidList) {
        Map<String, List<String>> map = new HashMap<>();
        for (String oid : oidList) {
            String neOid = getNeOid(oid);
            map.computeIfAbsent(neOid, k -> new ArrayList<>()).add(oid);
        }
        return map;
    }
}
