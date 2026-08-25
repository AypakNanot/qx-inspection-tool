package com.optel.qxinspection.qx.message;

import java.nio.ByteBuffer;

/**
 * 0x2410 激光器属性查询请求 (8 bytes)
 */
public class LaserAttributeRequest {
    private int subcaseNo = 1;
    private int slotId;
    private int portType;
    private int portSubType;
    private int portId;

    public LaserAttributeRequest(int slotId, int portType, int portSubType, int portId) {
        this.slotId = slotId;
        this.portType = portType;
        this.portSubType = portSubType;
        this.portId = portId;
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
