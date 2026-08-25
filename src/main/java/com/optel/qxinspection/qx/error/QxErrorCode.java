package com.optel.qxinspection.qx.error;

/**
 * Qx 协议错误码常量（精简版，仅保留巡检工具常用的）。
 */
public final class QxErrorCode {

    private QxErrorCode() {}

    public static final int SUCCESS = 0;
    public static final int OTHER_ERROR = 5;
    public static final int NE_NOT_SUPPORT_COMMAND = 8;
    public static final int SUBCASE_ERROR = 56;
    public static final int SLOT_ERROR = 57;
    public static final int PORT_ERROR = 58;
    public static final int LOCATION_ERROR = 59;
    public static final int INVALID_VALUE = 60;
    public static final int READ_ONLY = 61;
    public static final int NO_ENABLE = 62;
    public static final int SUBTYPE_UNKNOWN = 63;
    public static final int DB_ACT_ERROR = 64;
    public static final int COMMON_ERROR = 53;
    public static final int LASER_MANUAL_ERROR = 79;
    public static final int REQ_PORT_INEXIST = 80;
    public static final int REAL_PORT_INEXIST = 81;
    public static final int DPRAM_COMM_FAIL = 84;
    public static final int CLOCK_ERROR = 131;
    public static final int CLOCK_LOCATION_ERROR = 132;
    public static final int SECURITY_ERROR = 140;
    public static final int USER_PASSWORD_ERROR = 145;

    /**
     * 根据错误码返回描述文字。
     */
    public static String describe(int code) {
        return switch (code) {
            case SUCCESS -> "成功";
            case OTHER_ERROR -> "其他错误";
            case NE_NOT_SUPPORT_COMMAND -> "网元不支持该命令";
            case SUBCASE_ERROR -> "子架错误";
            case SLOT_ERROR -> "槽位错误";
            case PORT_ERROR -> "端口错误";
            case LOCATION_ERROR -> "位置错误";
            case INVALID_VALUE -> "无效值";
            case READ_ONLY -> "只读";
            case NO_ENABLE -> "未使能";
            case SUBTYPE_UNKNOWN -> "未知子类型";
            case DB_ACT_ERROR -> "数据库操作错误";
            case COMMON_ERROR -> "通用错误";
            case LASER_MANUAL_ERROR -> "激光器手动操作错误";
            case REQ_PORT_INEXIST -> "请求端口不存在";
            case REAL_PORT_INEXIST -> "实际端口不存在";
            case DPRAM_COMM_FAIL -> "DPRAM通信失败";
            case CLOCK_ERROR -> "时钟错误";
            case CLOCK_LOCATION_ERROR -> "时钟位置错误";
            case SECURITY_ERROR -> "安全错误";
            case USER_PASSWORD_ERROR -> "用户密码错误";
            default -> "未知错误(" + code + ")";
        };
    }
}
