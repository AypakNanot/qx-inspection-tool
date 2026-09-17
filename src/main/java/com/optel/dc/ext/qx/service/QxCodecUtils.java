package com.optel.dc.ext.qx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optel.qx.cci.codec.MsgHead;
import com.optel.qxinspection.qx.error.QxErrorCode;

import java.nio.ByteBuffer;

/**
 * Static utility methods for Qx protocol codec services.
 *
 * <p>Extracted from {@link AbstractGeneratedQxService} to keep the service
 * base class focused on send/decode flow.</p>
 */
final class QxCodecUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QxCodecUtils() {}

    // ---- Hex / JSON formatting ----

    static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ---- Message header formatting ----

    static String formatAnnotated(String prefix, byte[] header) {
        if (header == null || header.length < MsgHead.HEAD_BYTE_LEN) {
            return null;
        }
        try {
            MsgHead h = MsgHead.wrap(header);
            String[] names   = {"msgLen", "ver", "opts", "reserve", "seqNum", "target", "source", "cmdCode", "result"};
            int[]    byteLen = {4, 1, 1, 2, 4, 4, 4, 2, 2};

            String[] decoded = {
                    String.valueOf(h.getMsgLen()),
                    String.valueOf(h.getVersion() & 0xFF),
                    h.needsResponse() ? "needRsp" : "noRsp",
                    "seg=" + ((h.getReserve() >> 8) & 0xFF) + "/" + (h.getReserve() & 0xFF),
                    h.getSeqNum() == 0 ? "trap" : (h.getSeqNum() == 0xFFFFFFFF ? "broadcast" : String.valueOf(h.getSeqNum())),
                    String.format("0x%08X", h.getTarget()),
                    String.format("0x%08X", h.getSource()),
                    String.format("0x%04X", h.getCmdCode() & 0xFFFF),
                    decodeResult(h.getResult())
            };

            int[] widths = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                widths[i] = Math.max(Math.max(names[i].length(), byteLen[i] * 3 - 1), decoded[i].length());
            }

            int prefixLen = prefix.length();
            StringBuilder line1 = new StringBuilder(prefix);
            StringBuilder line2 = new StringBuilder(" ".repeat(prefixLen));
            int offset = 0;
            for (int i = 0; i < names.length; i++) {
                line1.append("| ").append(pad(names[i], widths[i])).append(' ');
                line2.append("| ").append(pad(hexBytes(header, offset, byteLen[i]), widths[i])).append(' ');
                offset += byteLen[i];
            }
            line1.append('|');
            line2.append('|');

            StringBuilder line3 = new StringBuilder(" ".repeat(prefixLen));
            for (int i = 0; i < names.length; i++) {
                line3.append("| ").append(pad(decoded[i], widths[i])).append(' ');
            }
            line3.append('|');

            return line1 + "\n" + line2 + "\n" + line3;
        } catch (Exception e) {
            return null;
        }
    }

    private static String decodeResult(short result) {
        int r = result & 0xFFFF;
        if (r == 0) return "OK";
        return String.format("0x%04X(%s)", r, QxErrorCode.describe(r));
    }

    private static String hexBytes(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    // ---- Error detail decoding ----

    /**
     * Decode the unified error body for a failed Set command.
     *
     * <p>Layout follows {@code Optel_Set_Result} (12 bytes):
     * bSubCaseNo / bSlotID / bPortType / bPortSubType / wPortID /
     * wTSOrderID / wTSAttribute / result(Word).</p>
     *
     * @return detailed description of the error location; null if no body or insufficient length
     */
    static String decodeSetErrorDetail(byte[] raw) {
        if (raw == null || raw.length < 12) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int subcaseNo = buf.get() & 0xFF;
        int slotId = buf.get() & 0xFF;
        int portType = buf.get() & 0xFF;
        int portSubType = buf.get() & 0xFF;
        int portId = buf.getShort() & 0xFFFF;
        int tsOrderId = buf.getShort() & 0xFFFF;
        int tsAttribute = buf.getShort() & 0xFFFF;
        int itemResult = buf.getShort() & 0xFFFF;
        return "subcase=" + subcaseNo + " slot=" + slotId
                + " portType=0x" + String.format("%02X", portType)
                + " portSubType=0x" + String.format("%02X", portSubType)
                + " portId=" + portId + " tsOrderId=" + tsOrderId
                + " tsAttr=" + tsAttribute
                + " itemResult=" + itemResult + "(" + QxErrorCode.describe(itemResult) + ")";
    }
}