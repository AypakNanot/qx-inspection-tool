package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备接入配置实体类（SQLite本地存储）
 * 
 * @author Rwj
 * @since 2026-08-20
 */
@Data
@Entity
@Table(name = "device_access_config")
public class DeviceAccessConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 配置ID（主键）
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 网元ID（关联老库dmne.oid）
     */
    @Column(name = "ne_id", nullable = false, unique = true, length = 64)
    private String neId;

    /**
     * 网元名称
     */
    @Column(name = "ne_name", length = 100)
    private String neName;

    /**
     * 设备类型名称
     */
    @Column(name = "ne_type_name", length = 100)
    private String neTypeName;

    /**
     * 所属网络名称
     */
    @Column(name = "network_name", length = 200)
    private String networkName;

    /**
     * IP地址
     */
    @Column(name = "ip_addr", length = 50, nullable = false)
    private String ipAddr;

    /**
     * 端口号
     */
    @Column(name = "port")
    private Integer port;

    /**
     * 用户名
     */
    @Column(name = "username", length = 100)
    private String username;

    /**
     * 密码
     */
    @Column(name = "password", length = 200)
    private String password;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    private Boolean enabled = true;

    /**
     * 连接状态：0-未连接，1-已连接，2-连接失败
     */
    @Column(name = "connection_status")
    private Integer connectionStatus = 0;

    /**
     * 最后连接时间
     */
    @Column(name = "last_connect_time")
    private LocalDateTime lastConnectTime;

    /**
     * 创建时间
     */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /**
     * 备注
     */
    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 保存前自动设置时间
     */
    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 更新前自动设置时间
     */
    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
