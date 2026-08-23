package com.optel.qxinspection.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * 网元设备实体类（对应老库dmne表）
 */
@Data
@Entity
@Table(name = "dmne")
public class DmNe implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 网元ID（主键，varchar(64)）
     */
    @Id
    @Column(name = "oid", nullable = false, length = 64)
    private String oid;

    /**
     * 设备类型（关联defdmne.neType）
     */
    @Column(name = "type")
    private Integer type;

    /**
     * 安装日期
     */
    @Column(name = "installDate", length = 32)
    private String installDate;

    /**
     * 位置
     */
    @Column(name = "location", length = 255)
    private String location;

    /**
     * 联系人
     */
    @Column(name = "contact", length = 255)
    private String contact;

    /**
     * 通信状态
     */
    @Column(name = "commuState")
    private Integer commuState;

    /**
     * 上报使能
     */
    @Column(name = "reportEnable")
    private Integer reportEnable;
}
