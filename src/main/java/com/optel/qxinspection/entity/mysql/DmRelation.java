package com.optel.qxinspection.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * 网元-网络归属关系（对应老库dmrelation表）
 * type=1 表示网元归属于网络
 */
@Data
@Entity
@Table(name = "dmrelation")
@IdClass(DmRelationId.class)
public class DmRelation implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "oid", nullable = false, length = 64)
    private String oid;

    @Column(name = "reo", length = 64)
    private String reo;

    @Id
    @Column(name = "type", nullable = false)
    private Integer type;
}
