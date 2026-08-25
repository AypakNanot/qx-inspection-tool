package com.optel.dc.ext.qx.service.impl;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class QxSendResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success;
    private byte[] rawPayload;
    private Integer deviceErrorCode;
    private String deviceErrorMessage;
    private long elapsedMs;

    public static QxSendResult ok(byte[] rawPayload, long elapsedMs) {
        return QxSendResult.builder().success(true)
                .rawPayload(rawPayload)
                .elapsedMs(elapsedMs).build();
    }

    public static QxSendResult fail(int code, String msg, long elapsedMs) {
        return QxSendResult.builder().success(false)
                .deviceErrorCode(code).deviceErrorMessage(msg).elapsedMs(elapsedMs).build();
    }
}
