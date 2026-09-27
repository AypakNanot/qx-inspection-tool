package com.optel.qxinspection.service;

/**
 * QX 0x0C07 性能代码常量（**设备真实编码**）。
 * <p>来源：Uniview 库 defpmattr.type **减 10000** 才是设备侧编码——
 * RS-ES 18194→8194、RS-SES 18195→8195、RS-UAS 18196→8196。
 * 请求侧三个选择器字段传 0xFFFF 全查，本常量仅用于回包 performanceCode 匹配。</p>
 */
final class PerfCodes {

    /** RS误码秒（defpmattr 18194 − 10000） */
    static final int RS_ES = 8194;
    /** RS严重误码秒（defpmattr 18195 − 10000） */
    static final int RS_SES = 8195;
    /** RS不可用秒（defpmattr 18196 − 10000） */
    static final int RS_UAS = 8196;

    private PerfCodes() {
    }

    /** 性能代码 → 指标名（未知代码返回数字串），用于 error_info 定位出错指标 */
    static String nameOf(int code) {
        if (code == RS_ES) return "RS-ES";
        if (code == RS_SES) return "RS-SES";
        if (code == RS_UAS) return "RS-UAS";
        return String.valueOf(code);
    }
}
