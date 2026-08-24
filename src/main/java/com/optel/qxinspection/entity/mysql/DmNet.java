package com.optel.qxinspection.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * 网络（对应老库dmnet表）
 */
@Data
@Entity
@Table(name = "dmnet")
public class DmNet implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "oid", nullable = false, length = 64)
    private String oid;

    @Column(name = "type")
    private Integer type;
}
