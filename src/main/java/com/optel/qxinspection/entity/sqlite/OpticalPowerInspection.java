package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 光功率巡检记录实体类（SQLite本地存储）
 * 
 * @author Rwj
 * @since 2026-08-20
 */
@Data
@Entity
@Table(name = "optical_power_inspection")
public class OpticalPowerInspection implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录ID（主键）
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 网元ID（关联dmne.oid）
     */
    @Column(name = "ne_id", nullable = false, length = 64)
    private String neId;

    /**
     * 网元名称
     */
    @Column(name = "ne_name", length = 100)
    private String neName;

    /**
     * 槽位号
     */
    @Column(name = "slot_no")
    private Integer slotNo;

    /**
     * 端口号
     */
    @Column(name = "port_no")
    private Integer portNo;

    /**
     * 光模块类型
     */
    @Column(name = "module_type", length = 100)
    private String moduleType;

    /**
     * 发送光功率（dBm）
     */
    @Column(name = "tx_power")
    private Double txPower;

    /**
     * 接收光功率（dBm）
     */
    @Column(name = "rx_power")
    private Double rxPower;

    /**
     * 发送光功率状态：0-正常，1-越下限，2-越上限
     */
    @Column(name = "tx_power_status")
    private Integer txPowerStatus = 0;

    /**
     * 接收光功率状态：0-正常，1-越下限，2-越上限
     */
    @Column(name = "rx_power_status")
    private Integer rxPowerStatus = 0;

    /**
     * 低光功率门限（dBm）
     */
    @Column(name = "low_threshold")
    private Double lowThreshold;

    /**
     * 高光功率门限（dBm）
     */
    @Column(name = "high_threshold")
    private Double highThreshold;

    /**
     * 巡检时间
     */
    @Column(name = "inspection_time")
    private LocalDateTime inspectionTime;

    /**
     * 巡检批次号
     */
    @Column(name = "batch_no", length = 50)
    private String batchNo;

    /**
     * 创建时间
     */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /**
     * 备注
     */
    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 保存前自动设置时间
     */
    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        if (this.inspectionTime == null) {
            this.inspectionTime = LocalDateTime.now();
        }
    }
}
