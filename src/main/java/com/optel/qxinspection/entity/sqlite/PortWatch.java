package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 端口关注标记
 */
@Data
@Entity
@Table(name = "port_watched", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"ne_id", "slot_no", "port_no"})
})
public class PortWatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ne_id", nullable = false, length = 64)
    private String neId;

    @Column(name = "slot_no", nullable = false)
    private Integer slotNo;

    @Column(name = "port_no", nullable = false)
    private Integer portNo;

    @Column(name = "port_name", length = 128)
    private String portName;

    @Column(name = "ne_name", length = 128)
    private String neName;
}
