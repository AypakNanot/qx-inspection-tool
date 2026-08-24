package com.optel.qxinspection.qx.message;

import lombok.Data;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 0x2410 激光器属性查询响应 (100 bytes, 2050风格)
 */
@Data
public class LaserAttributeResponse {
    // 端口定位（回显请求）
    private int subcaseNo;
    private int slotId;
    private int portType;
    private int portSubType;
    private int portId;

    // 激光器属性
    private int laserWave;        // 波长：1=1310nm, 2=1550nm, 3=850nm
    private int autoProState1;    // ALS自动开关协议状态
    private int autoProState2;
    private int manualControl;    // 手动打开
    private int laserState;       // 激光器实际状态：1=开, 2=关
    private int delayTime;        // ALS延迟时间

    // 模块属性
    private int laserType;        // 速率：1=2.5G, 2=622M, 3=155M, 4=10G, 0x10=GE
    private int distance;         // 距离档：1=I(短), 2=S(中), 3=L(长), 4=V(超长); GE: 0x10=SX, 0x11=LX
    private int backup1;
    private int backup2;
    private String vendorName;    // 厂商ASCII (16 bytes)
    private String partNumber;    // 模块型号编码ASCII (16 bytes)
    private String serialNumber;  // 序列号 (16 bytes)
    private String laserVersion;  // 版本号 (4 bytes)
    private String productDate;   // 生产日期 (8 bytes: 年2+月2+日2+空闲2)
    private int laserOpenTime;    // LOS后激光器打开时间

    // 光功率
    private int supportFlag;      // bit0: 1=支持光功率查询, 0=不支持
    private float recvLaserPower; // 接收光功率 dBm
    private float tranLaserPower; // 发送光功率 dBm

    public boolean isSupported() {
        return (supportFlag & 0x01) == 1;
    }

    public String getLaserTypeName() {
        return switch (laserType) {
            case 1 -> "2.5G";
            case 2 -> "622M";
            case 3 -> "155M";
            case 4 -> "10G";
            case 0x10 -> "GE";
            default -> "Unknown(" + laserType + ")";
        };
    }

    public String getDistanceName() {
        if (laserType == 0x10) { // GE专用
            return switch (distance) {
                case 0x10 -> "SX";
                case 0x11 -> "LX";
                default -> "Unknown(" + distance + ")";
            };
        }
        return switch (distance) {
            case 1 -> "I";
            case 2 -> "S";
            case 3 -> "L";
            case 4 -> "V";
            default -> "Unknown(" + distance + ")";
        };
    }

    public String getModuleTypeKey() {
        return getLaserTypeName() + "-" + getDistanceName();
    }

    public String getLaserWaveName() {
        return switch (laserWave) {
            case 1 -> "1310nm";
            case 2 -> "1550nm";
            case 3 -> "850nm";
            default -> "Unknown(" + laserWave + ")";
        };
    }

    public static LaserAttributeResponse decode(byte[] payload) {
        LaserAttributeResponse resp = new LaserAttributeResponse();
        ByteBuffer buf = ByteBuffer.wrap(payload);

        // 端口定位 (4+2=6 bytes)
        resp.subcaseNo = buf.get() & 0xFF;
        resp.slotId = buf.get() & 0xFF;
        resp.portType = buf.get() & 0xFF;
        resp.portSubType = buf.get() & 0xFF;
        resp.portId = buf.getShort() & 0xFFFF;

        // 激光器属性
        resp.laserWave = buf.get() & 0xFF;
        resp.autoProState1 = buf.get() & 0xFF;
        resp.autoProState2 = buf.get() & 0xFF;
        resp.manualControl = buf.get() & 0xFF;
        resp.laserState = buf.get() & 0xFF;
        resp.delayTime = buf.getShort() & 0xFFFF;

        // 模块属性
        resp.laserType = buf.get() & 0xFF;
        resp.distance = buf.get() & 0xFF;
        resp.backup1 = buf.get() & 0xFF;
        resp.backup2 = buf.get() & 0xFF;

        // 字符串字段
        resp.vendorName = readFixedString(buf, 16);
        resp.partNumber = readFixedString(buf, 16);
        resp.serialNumber = readFixedString(buf, 16);
        resp.laserVersion = readFixedString(buf, 4);
        resp.productDate = readFixedString(buf, 8);

        // 其他
        resp.laserOpenTime = buf.get() & 0xFF;
        buf.position(buf.position() + 6); // bmBackup[6]

        // 光功率
        resp.supportFlag = buf.get() & 0xFF;
        resp.recvLaserPower = buf.getFloat();
        resp.tranLaserPower = buf.getFloat();

        return resp;
    }

    private static String readFixedString(ByteBuffer buf, int len) {
        byte[] bytes = new byte[len];
        buf.get(bytes);
        // 找到第一个 null 字节
        int end = 0;
        while (end < len && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.US_ASCII).trim();
    }
}
