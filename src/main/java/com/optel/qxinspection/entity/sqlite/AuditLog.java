package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 操作审计日志
 */
@Data
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作时间 */
    @Column(name = "op_time", nullable = false)
    private LocalDateTime opTime;

    /** 操作类型: CONNECT, DISCONNECT, INSPECTION, CONFIG, SYNC, THRESHOLD, BACKUP, RESTORE */
    @Column(name = "op_type", nullable = false, length = 32)
    private String opType;

    /** 操作对象 */
    @Column(name = "target", length = 128)
    private String target;

    /** 操作结果: SUCCESS, FAIL */
    @Column(name = "result", length = 16)
    private String result;

    /** 备注 */
    @Column(name = "remark", length = 500)
    private String remark;
}
