package com.optel.qxinspection.entity.sqlite;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.io.Serializable;

/**
 * 链路巡检结果（A端+Z端在同一行显示）
 */
@Data
public class LinkInspectionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 序号 */
    private Integer seqNo;

    /** 链路名称（dmconnection.name） */
    private String linkName;

    /** 链路OID */
    private String linkOid;

    // ========== A端信息 ==========
    /** A端网元ID */
    @JsonProperty("aNeId")
    private String aNeId;
    /** A端网元名称 */
    @JsonProperty("aNeName")
    private String aNeName;
    /** A端网元类型 */
    @JsonProperty("aNeTypeName")
    private String aNeTypeName;
    /** A端端口名称 */
    @JsonProperty("aPortName")
    private String aPortName;
    /** A端光模块类型 */
    @JsonProperty("aModuleType")
    private String aModuleType;
    /** A端发光功率(dBm) */
    @JsonProperty("aTxPower")
    private Double aTxPower;
    /** A端收光功率(dBm) */
    @JsonProperty("aRxPower")
    private Double aRxPower;
    /** A端发光状态 */
    @JsonProperty("aTxStatus")
    private String aTxStatus;
    /** A端收光状态 */
    @JsonProperty("aRxStatus")
    private String aRxStatus;
    /** A端B1差错率 */
    @JsonProperty("aB1Error")
    private String aB1Error;
    /** A端带宽利用率 */
    @JsonProperty("aBandwidthUsage")
    private Double aBandwidthUsage;

    // ========== Z端信息 ==========
    /** Z端网元ID */
    @JsonProperty("zNeId")
    private String zNeId;
    /** Z端网元名称 */
    @JsonProperty("zNeName")
    private String zNeName;
    /** Z端网元类型 */
    @JsonProperty("zNeTypeName")
    private String zNeTypeName;
    /** Z端端口名称 */
    @JsonProperty("zPortName")
    private String zPortName;
    /** Z端光模块类型 */
    @JsonProperty("zModuleType")
    private String zModuleType;
    /** Z端发光功率(dBm) */
    @JsonProperty("zTxPower")
    private Double zTxPower;
    /** Z端收光功率(dBm) */
    @JsonProperty("zRxPower")
    private Double zRxPower;
    /** Z端发光状态 */
    @JsonProperty("zTxStatus")
    private String zTxStatus;
    /** Z端收光状态 */
    @JsonProperty("zRxStatus")
    private String zRxStatus;
    /** Z端B1差错率 */
    @JsonProperty("zB1Error")
    private String zB1Error;
    /** Z端带宽利用率 */
    @JsonProperty("zBandwidthUsage")
    private Double zBandwidthUsage;

    // ========== 门限信息 ==========
    /** A端发送低门限 */
    @JsonProperty("aTxLowThreshold")
    private Double aTxLowThreshold;
    /** A端发送高门限 */
    @JsonProperty("aTxHighThreshold")
    private Double aTxHighThreshold;
    /** A端接收低门限 */
    @JsonProperty("aRxLowThreshold")
    private Double aRxLowThreshold;
    /** A端接收高门限 */
    @JsonProperty("aRxHighThreshold")
    private Double aRxHighThreshold;
    /** Z端发送低门限 */
    @JsonProperty("zTxLowThreshold")
    private Double zTxLowThreshold;
    /** Z端发送高门限 */
    @JsonProperty("zTxHighThreshold")
    private Double zTxHighThreshold;
    /** Z端接收低门限 */
    @JsonProperty("zRxLowThreshold")
    private Double zRxLowThreshold;
    /** Z端接收高门限 */
    @JsonProperty("zRxHighThreshold")
    private Double zRxHighThreshold;
}