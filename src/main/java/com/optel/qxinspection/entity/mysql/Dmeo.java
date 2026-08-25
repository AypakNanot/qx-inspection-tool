package com.optel.qxinspection.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * 统一对象实体（对应老库dmeo表）
 * cid: 2=NE, 3=Subrack, 4=Package/Board(盘), 5=Port(端口), 6=Channel
 */
@Data
@Entity
@Table(name = "dmeo")
public class Dmeo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "oid", nullable = false, length = 64)
    private String oid;

    /** 类别：2=NE, 4=盘, 5=端口 */
    @Column(name = "cid")
    private Integer cid;

    /** 子类型 */
    @Column(name = "type")
    private Integer type;

    /** 名称 */
    @Column(name = "name", length = 256)
    private String name;

    /** 定义名称 */
    @Column(name = "defName", length = 256)
    private String defName;
}
