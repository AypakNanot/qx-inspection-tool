package com.optel.dc.ext.qx.service;

import com.optel.dc.ext.qx.service.impl.QxSendResult;

/**
 * Qx 设备通信内部接口。
 *
 * <p>Retains only two methods that cannot go through qx-api:</p>
 * <ul>
 *   <li>{@link #send} -- used by controller's debug send endpoint, not part of the external contract.</li>
 *   <li>{@link #shouldReconnect} -- used by {@code QxReconnectManager} for reconnect decisions,
 *       unrelated to connection lifecycle.</li>
 * </ul>
 */
public interface IQxDeviceService {

    /**
     * 同步发送一条 Qx 协议指令并等待响应。
     *
     * @param neId      网元连接 key
     * @param cmdCode   协议命令码（如 0x2401）
     * @param payload   负载字节数组（可为空）
     * @param timeoutMs 超时时间（毫秒），null 使用默认 10s
     * @return 响应结果或失败描述
     */
    QxSendResult send(String neId, int cmdCode, byte[] payload, Integer timeoutMs);

    /**
     * 判断指定 NE 是否应该重连。
     * 当端点仍已注册且 autoConnect 启用时返回 true。
     */
    boolean shouldReconnect(String neId);
}