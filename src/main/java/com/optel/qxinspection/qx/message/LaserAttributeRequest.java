package com.optel.qxinspection.qx.message;

import java.nio.ByteBuffer;

/**
 * 0x2410 激光器属性查询请求 (8 bytes)
 */
public class LaserAttributeRequest {
    private int subcaseNo = 1;
    private int slotId;
    private int portType = 0xFF;
    private int portSubType = 0xFF;
    private int portId = 0xFFFF;

    public LaserAttributeRequest(int slotId) {
        this.slotId = slotId;
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put((byte) (subcaseNo & 0xFF));
        buf.put((byte) (slotId & 0xFF));
        buf.put((byte) (portType & 0xFF));
        buf.put((byte) (portSubType & 0xFF));
        buf.putShort((short) (portId & 0xFFFF));
        buf.put((byte) 0); // backup1
        buf.put((byte) 0); // backup2
        return buf.array();
    }
}
