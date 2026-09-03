package com.optel.qxinspection.service;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MySQL 按需连接管理器
 * 同步时创建连接，完成后断开，参数从 YAML 默认值 + SQLite 覆盖读取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MysqlConnectionManager {

    private final SysConfigService sysConfigService;

    private static final String KEY_HOST = "mysql.host";
    private static final String KEY_PORT = "mysql.port";
    private static final String KEY_DATABASE = "mysql.database";
    private static final String KEY_USERNAME = "mysql.username";
    private static final String KEY_PASSWORD = "mysql.password";

    @Value("${app.mysql.host:127.0.0.1}")
    private String defaultHost;

    @Value("${app.mysql.port:3306}")
    private int defaultPort;

    @Value("${app.mysql.database:Uniview}")
    private String defaultDatabase;

    @Value("${app.mysql.username:}")
    private String defaultUsername;

    @Value("${app.mysql.password:}")
    private String defaultPassword;

    private volatile HikariDataSource dataSource;
    private volatile JdbcTemplate jdbcTemplate;

    /**
     * 获取 MySQL JdbcTemplate，不存在则创建
     */
    public synchronized JdbcTemplate getJdbcTemplate() {
        if (jdbcTemplate == null || dataSource == null || dataSource.isClosed()) {
            createDataSource();
        }
        return jdbcTemplate;
    }

    /**
     * 测试 MySQL 连接
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        String host = getEffectiveHost();
        int port = getEffectivePort();
        String database = getEffectiveDatabase();
        String username = getEffectiveUsername();
        String password = getEffectivePassword();
        log.info("=== 测试连接 === host='{}', port={}, database='{}', username='{}'",
                host, port, database, username);

        try (HikariDataSource testDs = createDataSource(host, port, database, username, password)) {
            try (Connection conn = testDs.getConnection();
                 var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()")) {
                long count = rs.next() ? rs.getLong(1) : 0;
                result.put("status", "SUCCESS");
                result.put("message", "连接成功，共 " + count + " 张表");
                result.put("tables", count);
            }
        } catch (Exception e) {
            result.put("status", "FAILED");
            String errMsg = e.getMessage();
            if (errMsg != null && errMsg.contains("Access denied")) {
                result.put("message", "连接失败: 用户名或密码错误");
            } else if (errMsg != null && errMsg.contains("Unknown host")) {
                result.put("message", "连接失败: 无法解析主机地址，请检查配置");
            } else if (errMsg != null && errMsg.contains("Connection refused")) {
                result.put("message", "连接失败: 目标主机拒绝连接，请检查地址和端口");
            } else if (errMsg != null && errMsg.contains("Communications link failure")) {
                result.put("message", "连接失败: 网络不通，请检查主机地址和端口");
            } else {
                result.put("message", "连接失败，请检查 MySQL 连接配置");
            }
            log.error("MySQL 连接测试失败", e);
        }
        return result;
    }

    /**
     * 保存 MySQL 配置到 SQLite（仅保存用户修改的字段）
     */
    public void saveConfig(String host, String username, String password) {
        log.info("保存MySQL配置: host={}, username={}", host, username);
        sysConfigService.set(KEY_HOST, host);
        sysConfigService.set(KEY_USERNAME, username);
        if (password != null && !password.isEmpty()) {
            sysConfigService.set(KEY_PASSWORD, password);
        }
        log.info("MySQL 配置已保存: {}@{}", username, host);
        close();
    }

    /**
     * 获取当前生效配置（YAML默认值 + SQLite覆盖）
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", getEffectiveHost());
        config.put("port", getEffectivePort());
        config.put("database", getEffectiveDatabase());
        config.put("username", getEffectiveUsername());
        String pwd = getEffectivePassword();
        config.put("password", pwd.isEmpty() ? "" : "******");
        config.put("configured", true);
        return config;
    }

    public String getPassword() {
        return getEffectivePassword();
    }

    public synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
                log.info("MySQL 连接池已关闭");
            } catch (Exception e) {
                log.warn("关闭 MySQL 连接池异常: {}", e.getMessage());
            }
        }
        dataSource = null;
        jdbcTemplate = null;
    }

    private String getEffectiveHost() {
        String v = sysConfigService.get(KEY_HOST, null);
        return (v != null && !v.isEmpty()) ? v : defaultHost;
    }

    private int getEffectivePort() {
        String v = sysConfigService.get(KEY_PORT, null);
        return (v != null && !v.isEmpty()) ? Integer.parseInt(v) : defaultPort;
    }

    private String getEffectiveDatabase() {
        String v = sysConfigService.get(KEY_DATABASE, null);
        return (v != null && !v.isEmpty()) ? v : defaultDatabase;
    }

    private String getEffectiveUsername() {
        String v = sysConfigService.get(KEY_USERNAME, null);
        return (v != null && !v.isEmpty()) ? v : defaultUsername;
    }

    private String getEffectivePassword() {
        String v = sysConfigService.get(KEY_PASSWORD, null);
        return (v != null && !v.isEmpty()) ? v : defaultPassword;
    }

    private void createDataSource() {
        String host = getEffectiveHost();
        int port = getEffectivePort();
        String database = getEffectiveDatabase();
        String username = getEffectiveUsername();
        String password = getEffectivePassword();

        this.dataSource = createDataSource(host, port, database, username, password);
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
        log.info("MySQL 连接池已创建: {}@{}:{}/{}", username, host, port, database);
    }

    private HikariDataSource createDataSource(String host, int port, String database,
                                               String username, String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                host, port, database));
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.mysql.jdbc.Driver");
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(10000);
        ds.setIdleTimeout(60000);
        ds.setMaxLifetime(300000);
        return ds;
    }
}
