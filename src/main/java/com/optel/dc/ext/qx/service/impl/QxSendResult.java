package com.optel.dc.ext.qx.service.impl;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

@Data
@Builder
public class QxSendResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success;
    private byte[] rawPayload;
    /** 24-byte response message header (MsgHead), used for COMM log formatting. */
    private byte[] responseHeader;
    /**
     * Derived from {@link #rawPayload} — for callers that still expect a UTF-8 string.
     */
    private String rawXml;
    private Integer deviceErrorCode;
    private String deviceErrorMessage;
    private long elapsedMs;

    public static QxSendResult ok(byte[] rawPayload, byte[] responseHeader, long elapsedMs) {
        return QxSendResult.builder().success(true)
                .rawPayload(rawPayload)
                .responseHeader(responseHeader)
                .rawXml(rawPayload != null ? new String(rawPayload, StandardCharsets.UTF_8) : null)
                .elapsedMs(elapsedMs).build();
    }

    public static QxSendResult fail(int code, String msg, long elapsedMs) {
        return fail(code, msg, elapsedMs, null, null);
    }

    /**
     * @param rawPayload device response body (when header result != 0, the device may
     *                    attach a unified error body for upper-layer detail decoding)
     */
    public static QxSendResult fail(int code, String msg, long elapsedMs, byte[] rawPayload) {
        return fail(code, msg, elapsedMs, rawPayload, null);
    }

    public static QxSendResult fail(int code, String msg, long elapsedMs,
                                    byte[] rawPayload, byte[] responseHeader) {
        return QxSendResult.builder().success(false)
                .deviceErrorCode(code).deviceErrorMessage(msg)
                .rawPayload(rawPayload).responseHeader(responseHeader)
                .elapsedMs(elapsedMs).build();
    }
}
