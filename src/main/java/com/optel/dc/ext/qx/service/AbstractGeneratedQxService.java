package com.optel.dc.ext.qx.service;

import com.optel.qxinspection.qx.error.QxErrorCode;
import com.optel.dc.ext.qx.service.impl.QxSendResult;
import com.optel.qx.cci.payload.QxPayloadCodec;

import java.nio.ByteBuffer;

/**
 * 自动生成的 Qx 协议服务的公共父类。
 *
 * <p>提供编解码、发送等基础能力。子类由 codec 插件按 YAML namespace 生成。</p>
 */
public abstract class AbstractGeneratedQxService extends AbstractQxService {

    protected final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass());

    private final IQxDeviceService qxDeviceService;

    protected AbstractGeneratedQxService(IQxDeviceService qxDeviceService,
                                         org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.qxDeviceService = qxDeviceService;
    }

    // ---- 编码 / 发送 / 解码 ----

    @SuppressWarnings("unchecked")
    protected final <T> byte[] encode(T data) {
        return codecRegistry.getByType(
                (Class<T>) data.getClass()).encode(data);
    }

    /**
     * 发送空负载 -> 读取 4 字节 int 结果 -> 非零则抛异常。
     */
    protected final void sendAndCheck(String neId, int cmdCode, String operation) {
        long sendTs = System.currentTimeMillis();
        QxSendResult result = qxDeviceService.send(neId, cmdCode, new byte[0], null);
        long recvTs = System.currentTimeMillis();
        int ms = (int) (recvTs - sendTs);

        if (!result.isSuccess()) {
            int deviceCode = result.getDeviceErrorCode() != null
                    ? result.getDeviceErrorCode() : QxErrorCode.OTHER_ERROR;
            String deviceMsg = result.getDeviceErrorMessage();
            log.error("{} failed: neId={}, cmdCode=0x{}, deviceResult={}, desc={}",
                    operation, neId, String.format("%04X", cmdCode & 0xFFFF), deviceCode, deviceMsg);
            throw new QxCommandException(deviceCode,
                    operation + " failed: [" + deviceCode + "] " + deviceMsg);
        }

        byte[] raw = result.getRawPayload();
        int deviceResult = ByteBuffer.wrap(raw).getInt();
        if (deviceResult != 0) {
            String desc = QxErrorCode.describe(deviceResult);
            log.error("{} failed: neId={}, cmdCode=0x{}, deviceResult={}, desc={}",
                    operation, neId, String.format("%04X", cmdCode & 0xFFFF), deviceResult, desc);
            throw new QxCommandException(deviceResult,
                    operation + " failed: [" + deviceResult + "] " + desc);
        }

        log.debug("{} OK: neId={}, cmdCode=0x{}, {}ms", operation, neId,
                String.format("%04X", cmdCode & 0xFFFF), ms);
    }

    /**
     * 编码 -> 发送 -> 读取 4 字节 int 结果 -> 非零则抛异常。
     */
    protected final <T> void sendAndCheck(String neId, int cmdCode, T data, String operation) {
        byte[] payload;
        try {
            payload = encode(data);
        } catch (Exception e) {
            log.error("{} encode failed: neId={}, cmdCode=0x{}", operation, neId,
                    String.format("%04X", cmdCode & 0xFFFF), e);
            throw e;
        }

        long sendTs = System.currentTimeMillis();
        QxSendResult result = qxDeviceService.send(neId, cmdCode, payload, null);
        long recvTs = System.currentTimeMillis();
        int ms = (int) (recvTs - sendTs);

        if (!result.isSuccess()) {
            int deviceCode = result.getDeviceErrorCode() != null
                    ? result.getDeviceErrorCode() : QxErrorCode.OTHER_ERROR;
            String deviceMsg = result.getDeviceErrorMessage();
            log.error("{} failed: neId={}, cmdCode=0x{}, deviceResult={}, desc={}",
                    operation, neId, String.format("%04X", cmdCode & 0xFFFF), deviceCode, deviceMsg);
            throw new QxCommandException(deviceCode,
                    operation + " failed: [" + deviceCode + "] " + deviceMsg);
        }

        byte[] raw = result.getRawPayload();
        int deviceResult = ByteBuffer.wrap(raw).getInt();
        if (deviceResult != 0) {
            String desc = QxErrorCode.describe(deviceResult);
            log.error("{} failed: neId={}, cmdCode=0x{}, deviceResult={}, desc={}",
                    operation, neId, String.format("%04X", cmdCode & 0xFFFF), deviceResult, desc);
            throw new QxCommandException(deviceResult,
                    operation + " failed: [" + deviceResult + "] " + desc);
        }

        log.debug("{} OK: neId={}, cmdCode=0x{}, {}ms", operation, neId,
                String.format("%04X", cmdCode & 0xFFFF), ms);
    }

    /**
     * 编码 -> 发送 -> 通过 type-indexed codec 解码响应。
     */
    protected final <Req, Resp> Resp sendAndDecode(String neId, int cmdCode, Req req,
                                                   Class<Resp> responseType, String operation) {
        byte[] payload;
        try {
            payload = encode(req);
        } catch (Exception e) {
            log.error("{} encode failed: neId={}, cmdCode=0x{}", operation, neId,
                    String.format("%04X", cmdCode & 0xFFFF), e);
            throw e;
        }

        QxPayloadCodec<Resp> codec;
        try {
            codec = getCodec(responseType, cmdCode, neId);
        } catch (Exception e) {
            log.error("{} codec lookup failed: neId={}, responseType={}", operation, neId,
                    responseType.getName(), e);
            throw e;
        }

        long sendTs = System.currentTimeMillis();
        QxSendResult result = qxDeviceService.send(neId, cmdCode, payload, null);
        long recvTs = System.currentTimeMillis();
        int ms = (int) (recvTs - sendTs);

        if (!result.isSuccess()) {
            int deviceCode = result.getDeviceErrorCode() != null
                    ? result.getDeviceErrorCode() : QxErrorCode.OTHER_ERROR;
            String deviceMsg = result.getDeviceErrorMessage();
            log.error("{} failed: neId={}, cmdCode=0x{}, deviceResult={}, desc={}",
                    operation, neId, String.format("%04X", cmdCode & 0xFFFF), deviceCode, deviceMsg);
            throw new QxCommandException(deviceCode,
                    operation + " failed: [" + deviceCode + "] " + deviceMsg);
        }
        byte[] raw = result.getRawPayload();

        Resp decoded;
        try {
            decoded = codec.decode(raw);
        } catch (Exception e) {
            log.error("{} decode failed: neId={}, cmdCode=0x{}, rawLen={}", operation, neId,
                    String.format("%04X", cmdCode & 0xFFFF), raw.length, e);
            throw e;
        }

        log.debug("{} OK: neId={}, cmdCode=0x{}, {}ms", operation, neId,
                String.format("%04X", cmdCode & 0xFFFF), ms);
        return decoded;
    }

    /**
     * 发送空负载 -> 通过 type-indexed codec 解码响应。
     */
    protected final <Resp> Resp sendAndDecode(String neId, int cmdCode,
                                              Class<Resp> responseType, String operation) {
        QxPayloadCodec<Resp> codec;
        try {
            codec = getCodec(responseType, cmdCode, neId);
        } catch (Exception e) {
            log.error("{} codec lookup failed: neId={}, responseType={}", operation, neId,
                    responseType.getName(), e);
            throw e;
        }

        long sendTs = System.currentTimeMillis();
        QxSendResult result = qxDeviceService.send(neId, cmdCode, new byte[0], null);
        long recvTs = System.currentTimeMillis();
        int ms = (int) (recvTs - sendTs);

        if (!result.isSuccess()) {
            int deviceCode = result.getDeviceErrorCode() != null
                    ? result.getDeviceErrorCode() : QxErrorCode.OTHER_ERROR;
            String deviceMsg = result.getDeviceErrorMessage();
            log.error("{} failed: neId={}, cmdCode=0x{}, deviceResult={}, desc={}",
                    operation, neId, String.format("%04X", cmdCode & 0xFFFF), deviceCode, deviceMsg);
            throw new QxCommandException(deviceCode,
                    operation + " failed: [" + deviceCode + "] " + deviceMsg);
        }
        byte[] raw = result.getRawPayload();

        Resp decoded;
        try {
            decoded = codec.decode(raw);
        } catch (Exception e) {
            log.error("{} decode failed: neId={}, cmdCode=0x{}, rawLen={}", operation, neId,
                    String.format("%04X", cmdCode & 0xFFFF), raw.length, e);
            throw e;
        }

        log.debug("{} OK: neId={}, cmdCode=0x{}, {}ms", operation, neId,
                String.format("%04X", cmdCode & 0xFFFF), ms);
        return decoded;
    }

    // ---- 工具方法 ----

    @SuppressWarnings("unchecked")
    private <Resp> QxPayloadCodec<Resp> getCodec(Class<Resp> responseType, int cmdCode, String neId) {
        QxPayloadCodec<Resp> codec = codecRegistry.getByType(responseType);
        if (codec == null) {
            log.error("No codec registered for response type: {} (neId={}, cmdCode=0x{})",
                    responseType.getName(), neId, String.format("%04X", cmdCode & 0xFFFF));
            throw new QxCommandException(QxErrorCode.OTHER_ERROR,
                    "No codec registered for response type: " + responseType.getName());
        }
        return codec;
    }
}
