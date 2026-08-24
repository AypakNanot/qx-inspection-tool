package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 光功率门限规则
 * 匹配优先级: PART > MODULE > GLOBAL
 */
@Data
@Entity
@Table(name = "threshold_rule", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"level_type", "match_key"})
})
public class ThresholdRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 级别: GLOBAL / MODULE / PART */
    @Column(name = "level_type", nullable = false, length = 20)
    private String levelType;

    /** 匹配键: GLOBAL时为"GLOBAL"，MODULE时为moduleTypeKey(如2.5G-L)，PART时为partNumber */
    @Column(name = "match_key", nullable = false, length = 64)
    private String matchKey;

    /** 接收光功率低门限 (dBm) */
    @Column(name = "rx_low")
    private Double rxLow;

    /** 接收光功率高门限 (dBm) */
    @Column(name = "rx_high")
    private Double rxHigh;

    /** 发送光功率低门限 (dBm) */
    @Column(name = "tx_low")
    private Double txLow;

    /** 发送光功率高门限 (dBm) */
    @Column(name = "tx_high")
    private Double txHigh;

    /** 说明 */
    @Column(name = "description", length = 200)
    private String description;
}
