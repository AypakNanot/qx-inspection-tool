package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 光功率门限覆盖行（按模块类型）。
 * <p>只保存用户改出来的差异：没有该行的类型直接用 application.yml 的预置默认值。</p>
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