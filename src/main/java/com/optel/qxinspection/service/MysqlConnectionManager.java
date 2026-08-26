package com.optel.qxinspection.service;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MySQL 按需连接管理器
 * 同步时创建连接，完成后断开，参数从 SQLite 配置读取
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
     * @return 测试结果（含错误信息）
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        String host = sysConfigService.get(KEY_HOST, "");
        int port = Integer.parseInt(sysConfigService.get(KEY_PORT, "3306"));
        String database = sysConfigService.get(KEY_DATABASE, "");
        String username = sysConfigService.get(KEY_USERNAME, "");
        String password = sysConfigService.get(KEY_PASSWORD, "");
        log.info("=== 测试连接 === 从SQLite读取: host='{}', port={}, database='{}', username='{}', password='{}'",
                host, port, database, username, password.isEmpty() ? "(空)" : "****");

        if (host.isEmpty() || database.isEmpty()) {
            result.put("status", "FAILED");
            result.put("message", "请先配置 MySQL 连接参数");
            return result;
        }

        HikariDataSource testDs = null;
        try {
            testDs = createDataSource(host, port, database, username, password);
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
        } finally {
            if (testDs != null) {
                testDs.close();
            }
        }
        return result;
    }

    /**
     * 保存 MySQL 配置到 SQLite
     */
    public void saveConfig(String host, int port, String database, String username, String password) {
        log.info("保存MySQL配置: host={}, port={}, database={}, username={}, password={}",
                host, port, database, username, password.isEmpty() ? "(空)" : "****");
        sysConfigService.set(KEY_HOST, host);
        sysConfigService.set(KEY_PORT, String.valueOf(port));
        sysConfigService.set(KEY_DATABASE, database);
        sysConfigService.set(KEY_USERNAME, username);
        // 密码为空时保留旧密码
        if (password != null && !password.isEmpty()) {
            sysConfigService.set(KEY_PASSWORD, password);
        }
        log.info("MySQL 配置已保存: {}@{}:{}/{}", username, host, port, database);
        // 配置变更后关闭旧连接，下次同步时会用新参数重建
        close();
    }

    /**
     * 获取当前配置（隐藏密码）
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        String host = sysConfigService.get(KEY_HOST, "");
        String database = sysConfigService.get(KEY_DATABASE, "");
        String username = sysConfigService.get(KEY_USERNAME, "");
        String pwd = sysConfigService.get(KEY_PASSWORD, "");
        config.put("host", host);
        config.put("port", Integer.parseInt(sysConfigService.get(KEY_PORT, "3306")));
        config.put("database", database);
        config.put("username", username);
        config.put("password", pwd.isEmpty() ? "" : "******");
        config.put("configured", !host.isEmpty());
        log.info("获取MySQL配置: host='{}', database='{}', username='{}'", host, database, username);
        return config;
    }

    /**
     * 获取密码（内部使用）
     */
    public String getPassword() {
        return sysConfigService.get(KEY_PASSWORD, "");
    }

    /**
     * 关闭连接池
     */
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

    private void createDataSource() {
        String host = sysConfigService.get(KEY_HOST, "");
        int port = Integer.parseInt(sysConfigService.get(KEY_PORT, "3306"));
        String database = sysConfigService.get(KEY_DATABASE, "");
        String username = sysConfigService.get(KEY_USERNAME, "");
        String password = sysConfigService.get(KEY_PASSWORD, "");

        if (host.isEmpty() || database.isEmpty()) {
            throw new IllegalStateException("请先在数据维护页配置 MySQL 连接参数");
        }

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
