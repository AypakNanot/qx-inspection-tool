package com.optel.dc.ext.qx.service;

import lombok.Getter;

/**
 * Qx 命令执行异常，携带设备错误码。
 */
@Getter
public class QxCommandException extends RuntimeException {

    private final int errorCode;

    public QxCommandException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public QxCommandException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

}
