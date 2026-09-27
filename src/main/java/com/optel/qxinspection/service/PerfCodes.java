package com.optel.qxinspection.service;

/**
 * QX 0x0C07 性能代码常量。
 * <p>来源：Uniview 库 defpmattr 表（eName → type），网管与设备同一编号体系。
 * 真机联调时需确认回包 performanceCode 与此一致。</p>
 */
final class PerfCodes {

    /** RS误码秒 */
    static final int RS_ES = 18194;
    /** RS严重误码秒 */
    static final int RS_SES = 18195;
    /** RS不可用秒 */
    static final int RS_UAS = 18196;

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
