package com.optel.qxinspection.qx.message;

import lombok.Data;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 0x2406 物理端口安装查询响应
 */
@Data
public class PortQueryResponse {
    private int result;
    private int count;
    private List<PortRecord> records = new ArrayList<>();

    public static PortQueryResponse decode(byte[] payload) {
        PortQueryResponse resp = new PortQueryResponse();
        ByteBuffer buf = ByteBuffer.wrap(payload);
        resp.result = buf.getInt();
        resp.count = buf.getInt();
        for (int i = 0; i < resp.count; i++) {
            PortRecord r = new PortRecord();
            r.setSubcaseNo(buf.get() & 0xFF);
            r.setSlotId(buf.get() & 0xFF);
            r.setPortType(buf.get() & 0xFF);
            r.setPortSubType(buf.get() & 0xFF);
            r.setPortId(buf.getShort() & 0xFFFF);
            r.setReqPortType(buf.get() & 0xFF);
            r.setReqPortSubType(buf.get() & 0xFF);
            r.setRealPortType(buf.get() & 0xFF);
            r.setRealPortSubType(buf.get() & 0xFF);
            r.setAdminState(buf.get() & 0xFF);
            r.setRunState(buf.get() & 0xFF);
            resp.records.add(r);
        }
        return resp;
    }
}
