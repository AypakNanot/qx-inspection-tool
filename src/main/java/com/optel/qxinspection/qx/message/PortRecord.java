package com.optel.qxinspection.qx.message;

import lombok.Data;

/**
 * 0x2406 物理端口查询记录 (12 bytes per record)
 */
@Data
public class PortRecord {
    private int subcaseNo;
    private int slotId;
    private int portType;
    private int portSubType;
    private int portId;
    private int reqPortType;
    private int reqPortSubType;
    private int realPortType;
    private int realPortSubType;
    private int adminState;
    private int runState;
}
