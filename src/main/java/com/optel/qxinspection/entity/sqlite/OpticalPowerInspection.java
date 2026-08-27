package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 光功率巡检记录实体类（SQLite本地存储）
 */
@Data
@Entity
@Table(name = "optical_power_inspection", indexes = {
    @Index(name = "idx_opi_round", columnList = "round_id"),
    @Index(name = "idx_opi_ne", columnList = "ne_id"),
    @Index(name = "idx_opi_round_ne", columnList = "round_id,ne_id")
})
public class OpticalPowerInspection implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 巡检轮次ID */
    @Column(name = "round_id", nullable = false)
    private Long roundId;

    /** 网元ID（关联dmne.oid） */
    @Column(name = "ne_id", nullable = false, length = 64)
    private String neId;

    /** 网元名称 */
    @Column(name = "ne_name", length = 100)
    private String neName;

    /** 所属网络 */
    @Column(name = "network_name", length = 100)
    private String networkName;

    /** 设备类型名称 */
    @Column(name = "ne_type_name", length = 100)
    private String neTypeName;

    /** 槽位号 */
    @Column(name = "slot_no")
    private Integer slotNo;

    /** 端口号 */
    @Column(name = "port_no")
    private Integer portNo;

    /** 端口名称（用户自定义） */
    @Column(name = "port_name", length = 256)
    private String portName;

    /** 端口类型 */
    @Column(name = "port_type")
    private Integer portType;

    /** 端口子类型 */
    @Column(name = "port_sub_type")
    private Integer portSubType;

    /** 是否支持光功率查询 */
    @Column(name = "supported")
    private Boolean supported;

    /** 光模块速率类型（如 2.5G, 10G, GE） */
    @Column(name = "laser_type", length = 20)
    private String laserType;

    /** 光模块距离档（如 L, S, I, SX, LX） */
    @Column(name = "laser_distance", length = 20)
    private String laserDistance;

    /** 模块类型组合键（与老网管一致：L16.1, S4.1, 1000BASE-SX） */
    @Column(name = "module_type_key", length = 40)
    private String moduleTypeKey;

    /** 模块型号编码 */
    @Column(name = "part_number", length = 32)
    private String partNumber;

    /** 波长 */
    @Column(name = "laser_wave", length = 20)
    private String laserWave;

    /** 发送光功率（dBm） */
    @Column(name = "tx_power")
    private Double txPower;

    /** 接收光功率（dBm） */
    @Column(name = "rx_power")
    private Double rxPower;

    /** 发送光功率状态：0-正常，1-越下限，2-越上限 */
    @Column(name = "tx_power_status")
    private Integer txPowerStatus = 0;

    /** 接收光功率状态：0-正常，1-越下限，2-越上限 */
    @Column(name = "rx_power_status")
    private Integer rxPowerStatus = 0;

    /** 接收低光功率门限（dBm） */
    @Column(name = "low_threshold")
    private Double lowThreshold;

    /** 接收高光功率门限（dBm） */
    @Column(name = "high_threshold")
    private Double highThreshold;

    /** 发送低光功率门限（dBm） */
    @Column(name = "tx_low_threshold")
    private Double txLowThreshold;

    /** 发送高光功率门限（dBm） */
    @Column(name = "tx_high_threshold")
    private Double txHighThreshold;

    /** 巡检时间 */
    @Column(name = "inspection_time")
    private LocalDateTime inspectionTime;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /** 采集失败原因 */
    @Column(name = "fail_reason", length = 200)
    private String failReason;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        if (this.inspectionTime == null) {
            this.inspectionTime = LocalDateTime.now();
        }
    }
}
