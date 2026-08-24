package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 巡检轮次元数据
 */
@Data
@Entity
@Table(name = "inspection_round")
public class InspectionRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 触发方式：MANUAL / SCHEDULED */
    @Column(name = "trigger_type", length = 20, nullable = false)
    private String triggerType;

    /** 巡检范围：ALL / NETWORK / SINGLE */
    @Column(name = "scope_type", length = 20, nullable = false)
    private String scopeType;

    /** 范围参数（网络名或网元ID） */
    @Column(name = "scope_param", length = 200)
    private String scopeParam;

    /** 状态：RUNNING / COMPLETED / FAILED */
    @Column(name = "status", length = 20, nullable = false)
    private String status = "RUNNING";

    /** 目标设备总数 */
    @Column(name = "total_count")
    private Integer totalCount = 0;

    /** 已完成设备数 */
    @Column(name = "done_count")
    private Integer doneCount = 0;

    /** 失败设备数 */
    @Column(name = "fail_count")
    private Integer failCount = 0;

    /** 开始时间 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 结束时间 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    @PrePersist
    public void prePersist() {
        if (this.startTime == null) {
            this.startTime = LocalDateTime.now();
        }
    }
}
