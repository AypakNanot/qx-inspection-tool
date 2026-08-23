package com.optel.qxinspection.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * 网元通信配置实体类（对应老库emnecomm表）
 *
 * 每个设备有两条记录：
 * - ipAddr=0.0.0.0, state=0（inactive）
 * - ipAddr=真实IP, state=1（active）
 */
@Data
@Entity
@Table(name = "emnecomm")
@IdClass(EmNeCommId.class)
public class EmNeComm implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 网元ID（关联dmne.oid）
     */
    @Id
    @Column(name = "oid", nullable = false, length = 64)
    private String oid;

    /**
     * IP地址
     */
    @Id
    @Column(name = "ipAddr", nullable = false, length = 32)
    private String ipAddr;

    /**
     * 状态：0=inactive, 1=active
     */
    @Column(name = "state")
    private Integer state;
}
