package com.optel.dc.ext.qx.service;

import com.optel.dc.ext.qx.service.impl.QxSendResult;
import com.optel.qx.cci.payload.QxPayloadCodec;
import com.optel.qxinspection.qx.error.QxErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Arrays;

/**
 * 所有自动生成的 Qx 协议服务的公共基类。
 *
 * <p>提供编码/解码、发送、COMM 日志记录能力。
 * 子类由 codec 插件按 YAML namespace 生成，每个 namespace 一个实现类。</p>
 */
public abstract class AbstractGeneratedQxService extends AbstractQxService {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final IQxDeviceService qxDeviceService;
    private final ApplicationEventPublisher eventPublisher;

    protected AbstractGeneratedQxService(IQxDeviceService qxDeviceService,
                                         ApplicationEventPublisher eventPublisher) {
        this.qxDeviceService = qxDeviceService;
        this.eventPublisher = eventPublisher;
    }

    // ==================== 公开 API（生成代码直接调用） ====================

    /**
     * 发送空 payload → 校验设备结果。
     * 用于无请求体的 Set 命令。
     *
     * @param neId      网元连接 key
     * @param cmdCode   命令码
     * @param operation 操作描述（用于日志）
     */
    protected final void sendAndCheck(String neId, int cmdCode, String operation) {
        doSend(neId, cmdCode, operation, new byte[0], null);
    }

    /**
     * 编码 → 发送 → 校验设备结果。
     * 统一处理 Set 命令（单条记录）和 listRecord Set 命令（批量记录）。
     *
     * @param neId      网元连接 key
     * @param cmdCode   命令码
     * @param operation 操作描述（用于日志）
     * @param records   请求 POJO 记录（可变参数）
     */
    @SafeVarargs
    protected final <T> void sendAndCheck(String neId, int cmdCode, String operation, T... records) {
        doSend(neId, cmdCode, operation, encodePayload(records), resolveReqLog(records));
    }

    /**
     * 发送空 payload → 解码响应。用于无请求体的 Trap / response-only 命令。
     *
     * @param neId      网元连接 key
     * @param cmdCode   命令码
     * @param operation 操作描述
     * @param <Resp>    响应类型
     * @return 解码后的响应对象
     */
    @SuppressWarnings("unchecked")
    protected final <Resp> Resp sendAndDecode(String neId, int cmdCode, String operation) {
        return (Resp) sendAndDecodeRaw(neId, cmdCode, operation);
    }

    /**
     * 编码 → 发送 → 解码响应。单条记录 Get 的类型安全包装。
     *
     * @param neId      网元连接 key
     * @param cmdCode   命令码
     * @param req       请求 POJO
     * @param operation 操作描述
     * @param <Req>     请求类型
     * @param <Resp>    响应类型
     * @return 解码后的响应对象
     */
    @SuppressWarnings("unchecked")
    protected final <Req, Resp> Resp sendAndDecode(String neId, int cmdCode, Req req, String operation) {
        return (Resp) sendAndDecodeRaw(neId, cmdCode, operation, req);
    }

    /**
     * 编码 → 发送 → 校验结果 → 解码响应。
     * 统一处理 Get 命令（单条记录）和 listRecord Get 命令（批量记录）。
     * 响应 codec 按 cmdCode 查找。
     *
     * @param neId      网元连接 key
     * @param cmdCode   命令码
     * @param operation 操作描述（用于日志）
     * @param records   请求 POJO 记录（可变参数）
     * @return 解码后的响应对象（单条）或 List（listRecord）
     */
    @SafeVarargs
    protected final <T> Object sendAndDecodeRaw(String neId, int cmdCode, String operation, T... records) {
        QxPayloadCodec<?> codec = getResponseCodec(cmdCode, neId);
        byte[] payload = encodePayload(records);
        Object reqLog = resolveReqLog(records);

        QxSendResult result = doSend(neId, cmdCode, operation, payload, reqLog);
        byte[] raw = result.getRawPayload();

        Object decoded;
        try {
            decoded = codec.isListRecord() ? codec.decodeList(raw) : codec.decode(raw);
            if (decoded == null && !codec.isListRecord()) {
                log.warn("{}: neId={}, cmdCode=0x{}, 设备返回空body",
                        operation, neId, hexCmdCode(cmdCode));
            }
        } catch (Exception e) {
            logComm(neId, cmdCode, operation, false, 0L, 0,
                    QxCodecUtils.toHex(payload), QxCodecUtils.toJson(reqLog),
                    QxCodecUtils.toHex(raw), null,
                    "{\"errorType\":\"DECODE\",\"error\":\"" + QxCodecUtils.escapeJson(e.getMessage()) + "\"}",
                    result.getResponseHeader());
            throw e;
        }

        logComm(neId, cmdCode, operation, true, 0L, 0,
                QxCodecUtils.toHex(payload), QxCodecUtils.toJson(reqLog),
                QxCodecUtils.toHex(raw), QxCodecUtils.toJson(decoded), null,
                result.getResponseHeader());
        return decoded;
    }

    // ==================== 编码 ====================

    @SuppressWarnings("unchecked")
    private <T> byte[] encodeSingle(T data) {
        return codecRegistry.getByType(
                (Class<T>) data.getClass()).encode(data);
    }

    /**
     * 将一个或多个 POJO 记录编码为单个字节数组。
     * 单条记录直接编码；多条记录各自编码后拼接。
     */
    @SafeVarargs
    private final <T> byte[] encodePayload(T... records) {
        if (records.length == 1) {
            return encodeSingle(records[0]);
        }
        byte[][] chunks = new byte[records.length][];
        int totalLen = 0;
        for (int i = 0; i < records.length; i++) {
            chunks[i] = encodeSingle(records[i]);
            totalLen += chunks[i].length;
        }
        byte[] payload = new byte[totalLen];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, payload, offset, chunk.length);
            offset += chunk.length;
        }
        return payload;
    }

    // ==================== 内部发送实现 ====================

    private QxSendResult doSend(String neId, int cmdCode, String operation,
                                byte[] payload, Object reqLog) {
        long sendTs = System.currentTimeMillis();
        QxSendResult result = qxDeviceService.send(neId, cmdCode, payload, null);
        long recvTs = System.currentTimeMillis();
        int ms = (int) (recvTs - sendTs);

        if (!result.isSuccess()) {
            sendFailAndThrow(neId, cmdCode, operation, sendTs, recvTs, payload, reqLog, result);
        }

        logComm(neId, cmdCode, operation, true, sendTs, ms,
                QxCodecUtils.toHex(payload), QxCodecUtils.toJson(reqLog),
                QxCodecUtils.toHex(result.getRawPayload()), null,
                "{\"deviceResult\":0}", result.getResponseHeader());
        return result;
    }

    @SuppressWarnings("java:S107")
    private void sendFailAndThrow(String neId, int cmdCode, String operation,
                                  long sendTs, long recvTs,
                                  byte[] payload, Object reqLog, QxSendResult result) {
        int deviceCode = result.getDeviceErrorCode() != null
                ? result.getDeviceErrorCode() : QxErrorCode.OTHER_ERROR;
        String deviceMsg = result.getDeviceErrorMessage();
        String itemDetail = QxCodecUtils.decodeSetErrorDetail(result.getRawPayload());
        int ms = (int) (recvTs - sendTs);
        log.error("{} failed: neId={}, cmdCode=0x{}, deviceResult={}, desc={}, itemDetail={}",
                operation, neId, hexCmdCode(cmdCode), deviceCode, deviceMsg, itemDetail);
        String summary = "{\"errorType\":\"SEND\",\"deviceResult\":" + deviceCode
                + ",\"error\":\"" + QxCodecUtils.escapeJson(deviceMsg) + "\""
                + (itemDetail != null ? ",\"itemDetail\":\"" + QxCodecUtils.escapeJson(itemDetail) + "\"" : "")
                + "}";
        logComm(neId, cmdCode, operation, false, sendTs, ms,
                QxCodecUtils.toHex(payload), QxCodecUtils.toJson(reqLog),
                QxCodecUtils.toHex(result.getRawPayload()), null, summary,
                result.getResponseHeader());
        String msg = operation + " failed: [" + deviceCode + "] " + deviceMsg;
        throw new QxCommandException(deviceCode,
                itemDetail != null ? msg + " — " + itemDetail : msg);
    }

    // ==================== 日志/编解码辅助 ====================

    @SafeVarargs
    private final <T> Object resolveReqLog(T... records) {
        return records.length == 1 ? records[0] : Arrays.asList(records);
    }

    private String resolveNamespace(int cmdCode) {
        try {
            QxPayloadCodec<?> codec = codecRegistry.get((short) cmdCode);
            return codec != null ? codec.namespace() : null;
        } catch (Exception e) {
            log.warn("Failed to resolve namespace for cmdCode=0x{}", hexCmdCode(cmdCode), e);
            return null;
        }
    }

    private QxPayloadCodec<?> getResponseCodec(int cmdCode, String neId) {
        QxPayloadCodec<?> codec = codecRegistry.getResponse((short) cmdCode);
        if (codec == null) {
            log.error("No response codec registered for cmdCode=0x{} (neId={})",
                    hexCmdCode(cmdCode), neId);
            throw new QxCommandException(QxErrorCode.OTHER_ERROR,
                    "No response codec registered for cmdCode: 0x" + hexCmdCode(cmdCode));
        }
        return codec;
    }

    /**
     * COMM 日志（SLF4J）。
     */
    private void logComm(String neId, int cmdCode, String operation,
                         boolean ok, long sendTs, int ms,
                         String reqHex, String reqBean,
                         String rspHex, String rspBean, String summary,
                         byte[] responseHeader) {
        try {
            String cmd = String.format("0x%04X", cmdCode & 0xFFFF);
            String namespace = resolveNamespace(cmdCode);
            String annotatedHeader = QxCodecUtils.formatAnnotated("RSP HDR: ", responseHeader);
            if (log.isDebugEnabled()) {
                log.debug("COMM {} {} neId={} ns={} {}ms {} | req={} | rsp={} | {}",
                        ok ? "OK" : "FAIL", operation, neId, namespace, ms, cmd,
                        reqBean != null ? reqBean : reqHex,
                        rspBean != null ? rspBean : rspHex,
                        summary != null ? summary : "");
                if (annotatedHeader != null) {
                    log.debug("{}", annotatedHeader);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to log COMM: neId={}", neId, e);
        }
    }

    private static String hexCmdCode(int cmdCode) {
        return String.format("%04X", cmdCode & 0xFFFF);
    }
}