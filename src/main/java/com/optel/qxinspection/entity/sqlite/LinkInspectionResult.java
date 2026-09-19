package com.optel.qxinspection.entity.sqlite;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 链路巡检结果（每条链路一条记录，含 A 端 + Z 端完整数据）
 * <p>
 * 同时作为 JPA 实体（映射 link_inspection_result 表）和 API 响应 DTO。
 * 光功率状态为采集时按门限实时判定后的结果，门限值本身不落库。
 * </p>
 * <p>
 * A/Z 端字段必须显式标注 {@code @JsonProperty}：Lombok 为 {@code aNeName} 生成的取值器是
 * {@code getANeName()}，Jackson 会把开头连续的大写字母一起小写化，得到 {@code aneName} 而不是
 * 前端约定的 {@code aNeName}，导致整端 12 列在前端全部取不到值。
 * </p>
 */
@Data
@Entity
@Table(name = "link_inspection_result")
public class LinkInspectionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long id;

    @JsonIgnore
    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "link_oid")
    private String linkOid;

    @Column(name = "link_name")
    private String linkName;

    @Column(name = "create_time")
    private String createTime;

    /** 序号（API 响应用，不持久化） */
    @Transient
    private Integer seqNo;

    // ==================== A 端 ====================

    @JsonProperty("aNeId")
    @Column(name = "a_ne_id")
    private String aNeId;

    @JsonProperty("aNeName")
    @Column(name = "a_ne_name")
    private String aNeName;

    @JsonProperty("aNeTypeName")
    @Column(name = "a_ne_type_name")
    private String aNeTypeName;

    @JsonProperty("aPortOid")
    @Column(name = "a_port_oid")
    private String aPortOid;

    @JsonProperty("aPortName")
    @Column(name = "a_port_name")
    private String aPortName;

    @JsonProperty("aModuleType")
    @Column(name = "a_module_type")
    private String aModuleType;

    @JsonProperty("aTxPower")
    @Column(name = "a_tx_power")
    private Double aTxPower;

    @JsonProperty("aRxPower")
    @Column(name = "a_rx_power")
    private Double aRxPower;

    @JsonProperty("aTxStatus")
    @Column(name = "a_tx_status")
    private String aTxStatus;

    @JsonProperty("aRxStatus")
    @Column(name = "a_rx_status")
    private String aRxStatus;

    @JsonProperty("aRsErrorSec")
    @Column(name = "a_rs_error_sec")
    private Integer aRsErrorSec;

    /** 总带宽(Mbps)。库里存的是 VC-12 个数，读取时由 InspectionService 换算 */
    @JsonProperty("aTotalBandwidth")
    @Column(name = "a_total_bandwidth")
    private Double aTotalBandwidth;

    /** 已用带宽(Mbps)。库里存的是 VC-12 个数，读取时由 InspectionService 换算 */
    @JsonProperty("aUsedBandwidth")
    @Column(name = "a_used_bandwidth")
    private Double aUsedBandwidth;

    @JsonProperty("aBandwidthUsage")
    @Column(name = "a_bandwidth_usage")
    private Double aBandwidthUsage;

    @JsonProperty("aErrorInfo")
    @Column(name = "a_error_info")
    private String aErrorInfo;

    // ==================== Z 端 ====================

    @JsonProperty("zNeId")
    @Column(name = "z_ne_id")
    private String zNeId;

    @JsonProperty("zNeName")
    @Column(name = "z_ne_name")
    private String zNeName;

    @JsonProperty("zNeTypeName")
    @Column(name = "z_ne_type_name")
    private String zNeTypeName;

    @JsonProperty("zPortOid")
    @Column(name = "z_port_oid")
    private String zPortOid;

    @JsonProperty("zPortName")
    @Column(name = "z_port_name")
    private String zPortName;

    @JsonProperty("zModuleType")
    @Column(name = "z_module_type")
    private String zModuleType;

    @JsonProperty("zTxPower")
    @Column(name = "z_tx_power")
    private Double zTxPower;

    @JsonProperty("zRxPower")
    @Column(name = "z_rx_power")
    private Double zRxPower;

    @JsonProperty("zTxStatus")
    @Column(name = "z_tx_status")
    private String zTxStatus;

    @JsonProperty("zRxStatus")
    @Column(name = "z_rx_status")
    private String zRxStatus;

    @JsonProperty("zRsErrorSec")
    @Column(name = "z_rs_error_sec")
    private Integer zRsErrorSec;

    /** 总带宽(Mbps)。库里存的是 VC-12 个数，读取时由 InspectionService 换算 */
    @JsonProperty("zTotalBandwidth")
    @Column(name = "z_total_bandwidth")
    private Double zTotalBandwidth;

    /** 已用带宽(Mbps)。库里存的是 VC-12 个数，读取时由 InspectionService 换算 */
    @JsonProperty("zUsedBandwidth")
    @Column(name = "z_used_bandwidth")
    private Double zUsedBandwidth;

    @JsonProperty("zBandwidthUsage")
    @Column(name = "z_bandwidth_usage")
    private Double zBandwidthUsage;

    @JsonProperty("zErrorInfo")
    @Column(name = "z_error_info")
    private String zErrorInfo;
}
