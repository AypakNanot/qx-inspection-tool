package com.optel.qxinspection.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OID工具类单元测试
 */
class OidUtilTest {

    // ========== getSlotId ==========

    @Test
    void testGetSlotId_ValidOid() {
        // OID: 101:1:11:2:1 → segment[2]=11
        assertEquals(11, OidUtil.getSlotId("101:1:11:2:1"));
    }

    @Test
    void testGetSlotId_ShortOid() {
        // 不足3段 → 返回0
        assertEquals(0, OidUtil.getSlotId("101:1"));
    }

    @Test
    void testGetSlotId_EmptyOid() {
        assertEquals(0, OidUtil.getSlotId(""));
    }

    @Test
    void testGetSlotId_NullOid() {
        assertEquals(0, OidUtil.getSlotId(null));
    }

    // ========== getPortId ==========

    @Test
    void testGetPortId_ValidOid() {
        // OID: 101:1:11:2:1 → segment[3]=2
        assertEquals(2, OidUtil.getPortId("101:1:11:2:1"));
    }

    @Test
    void testGetPortId_ShortOid() {
        // 不足4段 → 返回0
        assertEquals(0, OidUtil.getPortId("101:1:11"));
    }

    @Test
    void testGetPortId_EmptyOid() {
        assertEquals(0, OidUtil.getPortId(""));
    }

    @Test
    void testGetPortId_NullOid() {
        assertEquals(0, OidUtil.getPortId(null));
    }

    // ========== getNeId ==========

    @Test
    void testGetNeId_ValidOid() {
        // OID: 101:1:11:2:1 → segment[0]=101
        assertEquals(101, OidUtil.getNeId("101:1:11:2:1"));
    }

    @Test
    void testGetNeId_SingleSegment() {
        assertEquals(42, OidUtil.getNeId("42"));
    }

    @Test
    void testGetNeId_EmptyOid() {
        assertEquals(0, OidUtil.getNeId(""));
    }

    @Test
    void testGetNeId_NullOid() {
        assertEquals(0, OidUtil.getNeId(null));
    }

    // ========== getSubrackId ==========

    @Test
    void testGetSubrackId_ValidOid() {
        // OID: 101:1:11:2:1 → segment[1]=1
        assertEquals(1, OidUtil.getSubrackId("101:1:11:2:1"));
    }

    @Test
    void testGetSubrackId_SingleSegment() {
        // 不足2段 → 返回默认值1
        assertEquals(1, OidUtil.getSubrackId("101"));
    }

    // ========== getTimeslot ==========

    @Test
    void testGetTimeslot_ValidOid() {
        // OID: 101:1:11:2:1 → segment[4]=1
        assertEquals(1, OidUtil.getTimeslot("101:1:11:2:1"));
    }

    @Test
    void testGetTimeslot_ShortOid() {
        assertEquals(0, OidUtil.getTimeslot("101:1:11:2"));
    }

    // ========== getEndSegment ==========

    @Test
    void testGetEndSegment_ValidOid() {
        assertEquals(1, OidUtil.getEndSegment("101:1:11:2:1"));
    }

    @Test
    void testGetEndSegment_EmptyOid() {
        assertEquals(0, OidUtil.getEndSegment(""));
    }

    // ========== parseSegments ==========

    @Test
    void testParseSegments_Valid() {
        int[] seg = OidUtil.parseSegments("101:1:11:2:1");
        assertNotNull(seg);
        assertEquals(5, seg.length);
        assertEquals(101, seg[0]);
        assertEquals(1, seg[1]);
        assertEquals(11, seg[2]);
        assertEquals(2, seg[3]);
        assertEquals(1, seg[4]);
    }

    @Test
    void testParseSegments_SingleSegment() {
        int[] seg = OidUtil.parseSegments("42");
        assertEquals(1, seg.length);
        assertEquals(42, seg[0]);
    }

    @Test
    void testParseSegments_Empty() {
        int[] seg = OidUtil.parseSegments("");
        assertEquals(0, seg.length);
    }

    @Test
    void testParseSegments_Null() {
        int[] seg = OidUtil.parseSegments(null);
        assertEquals(0, seg.length);
    }

    // ========== toOid ==========

    @Test
    void testToOid_Valid() {
        assertEquals("101:1:11:2:1", OidUtil.toOid(new int[]{101, 1, 11, 2, 1}));
    }

    @Test
    void testToOid_SingleSegment() {
        assertEquals("42", OidUtil.toOid(new int[]{42}));
    }

    @Test
    void testToOid_Empty() {
        assertEquals("", OidUtil.toOid(new int[]{}));
    }

    @Test
    void testToOid_Null() {
        assertEquals("", OidUtil.toOid(null));
    }

    // ========== 层级提取方法 ==========

    @Test
    void testGetNeOid() {
        assertEquals("101", OidUtil.getNeOid("101:1:11:2:1"));
    }

    @Test
    void testGetSubrackOid() {
        assertEquals("101:1", OidUtil.getSubrackOid("101:1:11:2:1"));
    }

    @Test
    void testGetSlotOid() {
        assertEquals("101:1:11", OidUtil.getSlotOid("101:1:11:2:1"));
    }

    @Test
    void testGetPortOid() {
        assertEquals("101:1:11:2", OidUtil.getPortOid("101:1:11:2:1"));
    }

    @Test
    void testGetParentOid() {
        assertEquals("101:1:11", OidUtil.getParentOid("101:1:11:2"));
    }

    @Test
    void testGetParentOid_SingleSegment() {
        assertEquals("42", OidUtil.getParentOid("42"));
    }

    // ========== 判断方法 ==========

    @Test
    void testIsNeLevel_True() {
        assertTrue(OidUtil.isNeLevel("101"));
    }

    @Test
    void testIsNeLevel_False() {
        assertFalse(OidUtil.isNeLevel("101:1"));
    }

    @Test
    void testIsPortLevel_True() {
        assertTrue(OidUtil.isPortLevel("101:1:11:2"));
    }

    @Test
    void testIsPortLevel_False() {
        assertFalse(OidUtil.isPortLevel("101:1:11"));
    }

    @Test
    void testIsChildOf_True() {
        assertTrue(OidUtil.isChildOf("101:1:11", "101:1"));
    }

    @Test
    void testIsChildOf_Equal() {
        assertTrue(OidUtil.isChildOf("101:1", "101:1"));
    }

    @Test
    void testIsChildOf_False() {
        assertFalse(OidUtil.isChildOf("101:2", "101:1"));
    }

    @Test
    void testIsChildOf_NullTarget() {
        assertFalse(OidUtil.isChildOf(null, "101:1"));
    }

    @Test
    void testIsChildOf_NullSource() {
        assertFalse(OidUtil.isChildOf("101:1", null));
    }

    // ========== 分组 ==========

    @Test
    void testGroupByNe() {
        List<String> oidList = List.of("101:1:11:2", "101:1:11:3", "102:1:5:1");
        Map<String, List<String>> result = OidUtil.groupByNe(oidList);
        assertEquals(2, result.size());
        assertEquals(2, result.get("101").size());
        assertEquals(1, result.get("102").size());
    }

    // ========== portSubType编解码 ==========

    @Test
    void testEncodePortSubType() {
        // byteNum=2, slotNum=11 → (2 << 5) | (11 & 0x1F) = 64 | 11 = 75 = 0x4B
        assertEquals(0x4B, OidUtil.encodePortSubType(2, 11));
    }

    @Test
    void testByteFromPortSubType() {
        // 0x4B = 75 → (75 >> 5) & 0x07 = 2
        assertEquals(2, OidUtil.byteFromPortSubType(0x4B));
    }

    @Test
    void testSlotFromPortSubType() {
        // 0x4B = 75 → 75 & 0x1F = 11
        assertEquals(11, OidUtil.slotFromPortSubType(0x4B));
    }

    @Test
    void testGetTsPortSubType() {
        // OID: 101:1:11:2:1 → slotNum=11, byteNum=2 → 0x4B
        assertEquals(0x4B, OidUtil.getTsPortSubType("101:1:11:2:1"));
    }

    @Test
    void testTsToOid() {
        assertEquals("101:1:11:2:1", OidUtil.tsToOid("101:1", 0x4B, 1));
    }

    @Test
    void testTsToOid_RoundTrip() {
        // tsToOid 输出 neOid:slotNum:byteNum:portId (4段)
        // getTsPortSubType 从最后3段提取 slotNum和byteNum
        // getPortId 取 segment[3]，即 byteNum 位置
        String neOid = "101:1";
        int slotNum = 11;
        int byteNum = 2;
        int portId = 1;
        int portSubType = OidUtil.encodePortSubType(byteNum, slotNum);
        String oid = OidUtil.tsToOid(neOid, portSubType, portId);
        assertEquals("101:1:11:2:1", oid);

        // 验证反向提取
        assertEquals(slotNum, OidUtil.slotFromPortSubType(portSubType));
        assertEquals(byteNum, OidUtil.byteFromPortSubType(portSubType));
        // tsToOid 输出的第4段是 portId
        assertEquals(portId, OidUtil.getEndSegment(oid));
    }
}
