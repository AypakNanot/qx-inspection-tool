package com.optel.qxinspection.entity.sqlite;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 连接配置（SQLite本地存储）
 * scope='GLOBAL' 为全局默认配置，scope='NE' 为单设备覆盖
 */
@Data
@Entity
@Table(name = "conn_profile",
       uniqueConstraints = @UniqueConstraint(columnNames = {"scope", "ne_oid"}))
@IdClass(ConnProfileId.class)
public class ConnProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "scope", nullable = false, length = 10)
    private String scope;

    @Id
    @Column(name = "ne_oid", nullable = false, length = 64)
    private String neOid;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "password", nullable = false, length = 200)
    private String password;

    @Column(name = "port", nullable = false)
    private Integer port = 9900;

    @Column(name = "auto_connect", nullable = false)
    private Integer autoConnect = 1;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
