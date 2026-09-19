package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 光功率门限规则（按模块类型）
 */
@Data
@Entity
@Table(name = "threshold_rule")
public class ThresholdRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模块类型匹配键（如 S16.1, 1000BASE-SX） */
    @Column(name = "match_key", nullable = false, unique = true, length = 64)
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