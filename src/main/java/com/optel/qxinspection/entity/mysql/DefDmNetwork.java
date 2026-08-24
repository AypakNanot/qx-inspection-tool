package com.optel.qxinspection.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * 网络类型定义（对应老库defdmnetwork表）
 */
@Data
@Entity
@Table(name = "defdmnetwork")
public class DefDmNetwork implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "type", nullable = false)
    private Integer type;

    @Column(name = "eName", length = 255)
    private String eName;

    @Column(name = "cName", length = 255)
    private String cName;
}
