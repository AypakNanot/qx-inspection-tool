package com.optel.qxinspection.qx;

import com.optel.qx.cci.channel.QxChannelManager;
import com.optel.qx.cci.codec.MsgHead;
import com.optel.qx.cci.util.ChannelID;
import com.optel.qxinspection.qx.message.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * Qx 协议命令服务 - 封装底层通信为高级业务方法
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QxCommandService {

    private final QxChannelManager qxChannelManager;

    private static final int DEFAULT_TIMEOUT = 10; // seconds

    /**
     * 查询设备物理端口列表 (0x2406)
     */
    public List<PortRecord> queryPorts(String ip, int port, String user, String password) {
        try {
            byte[] reqBytes = new PortQueryRequest().encode();
            byte[] respBytes = sendCommand(ip, port, user, password, (short) 0x2406, reqBytes);
            if (respBytes == null) {
                log.warn("0x2406 响应为空: {}:{}", ip, port);
                return Collections.emptyList();
            }
            // 跳过4字节result码，取payload
            byte[] payload = new byte[respBytes.length - 4];
            System.arraycopy(respBytes, 4, payload, 0, payload.length);
            // 插入一个假的result=0在前面
            ByteBuffer buf = ByteBuffer.allocate(4 + payload.length);
            buf.putInt(0);
            buf.put(payload);
            PortQueryResponse resp = PortQueryResponse.decode(buf.array());
            if (resp.getResult() != 0) {
                log.warn("0x2406 返回错误码: {}, 设备={}:{}", resp.getResult(), ip, port);
                return Collections.emptyList();
            }
            return resp.getRecords();
        } catch (Exception e) {
            log.error("0x2406 查询失败: {}:{}, {}", ip, port, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询单端口激光器属性 (0x2410)
     */
    public LaserAttributeResponse queryLaserAttribute(String ip, int port, String user, String password,
                                                       int slotId, int portType, int portSubType, int portId) {
        try {
            byte[] reqBytes = new LaserAttributeRequest(slotId, portType, portSubType, portId).encode();
            byte[] respBytes = sendCommand(ip, port, user, password, (short) 0x2410, reqBytes);
            if (respBytes == null) {
                log.warn("0x2410 响应为空: {}:{}, slot={}, port={}", ip, port, slotId, portId);
                return null;
            }
            return LaserAttributeResponse.decode(respBytes);
        } catch (Exception e) {
            log.error("0x2410 查询失败: {}:{}, slot={}, port={}, {}",
                    ip, port, slotId, portId, e.getMessage());
            return null;
        }
    }

    /**
     * 底层发送方法 - 通过 QxChannelManager 发送命令并返回响应 payload
     */
    private byte[] sendCommand(String ip, int port, String user, String password,
                                short cmdCode, byte[] payload) {
        ChannelID chId = new ChannelID(ip, port);
        byte[] userBytes = user != null ? user.getBytes() : new byte[0];
        byte[] pswBytes = password != null ? password.getBytes() : new byte[0];

        byte[] msgData = qxChannelManager.send(chId, userBytes, pswBytes, payload, cmdCode, DEFAULT_TIMEOUT);
        if (msgData == null || msgData.length <= MsgHead.HEAD_BYTE_LEN) {
            return null;
        }
        // 提取 payload（跳过24字节消息头）
        byte[] result = new byte[msgData.length - MsgHead.HEAD_BYTE_LEN];
        System.arraycopy(msgData, MsgHead.HEAD_BYTE_LEN, result, 0, result.length);
        return result;
    }
}
