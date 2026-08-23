package com.optel.qxinspection.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * 设备类型定义实体类（对应老库defdmne表）
 */
@Data
@Entity
@Table(name = "defdmne")
public class DefDmNe implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备类型（主键）
     */
    @Id
    @Column(name = "neType", nullable = false)
    private Integer neType;

    /**
     * 英文名称
     */
    @Column(name = "eName", length = 255)
    private String eName;

    /**
     * 中文名称
     */
    @Column(name = "cName", length = 255)
    private String cName;

    /**
     * 子类型ID
     */
    @Column(name = "subcid")
    private Integer subcid;

    /**
     * 子类型列表
     */
    @Column(name = "subTypes", length = 255)
    private String subTypes;

    /**
     * 分类（如OPTELSDH）
     */
    @Column(name = "category", length = 255)
    private String category;
}
