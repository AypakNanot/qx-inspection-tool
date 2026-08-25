package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 系统配置键值对（SQLite持久化）
 */
@Data
@Entity
@Table(name = "sys_config")
public class SysConfig {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(name = "config_value", length = 512)
    private String configValue;
}
