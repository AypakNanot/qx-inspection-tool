package com.optel.dc.ext.qx.service;

import com.optel.dc.ext.qx.service.impl.QxSendResult;

/**
 * Qx 设备通信内部接口。
 */
public interface IQxDeviceService {

    /**
     * 同步发送一条 Qx 协议指令并等待响应。
     */
    QxSendResult send(String neId, int cmdCode, byte[] payload, Integer timeoutMs);

    /**
     * 判断指定 NE 是否应该重连。
     */
    boolean shouldReconnect(String neId);
}
