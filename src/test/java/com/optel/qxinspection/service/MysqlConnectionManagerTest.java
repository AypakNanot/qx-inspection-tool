package com.optel.qxinspection.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * MySQL 按需连接池的借用/归还语义测试。
 * <p>同一时刻只应存在一个池，且最后一个借出者归还之前，任何路径都不能把它关掉——
 * 否则会掐断并发请求正在用的连接（表现为随机的 "HikariDataSource has been closed"）。</p>
 * <p>池是懒初始化的，本测试只构造对象不取连接，因此不会真的去连 MySQL。</p>
 */
class MysqlConnectionManagerTest {

    private MysqlConnectionManager manager;

    @BeforeEach
    void setUp() {
        manager = new MysqlConnectionManager(mock(SysConfigService.class));
        ReflectionTestUtils.setField(manager, "defaultHost", "127.0.0.1");
        ReflectionTestUtils.setField(manager, "defaultPort", 3306);
        ReflectionTestUtils.setField(manager, "defaultDatabase", "Uniview");
        ReflectionTestUtils.setField(manager, "defaultUsername", "user");
        ReflectionTestUtils.setField(manager, "defaultPassword", "pwd");
    }

    @Test
    void close_withAnotherBorrowerStillActive_keepsSamePool() {
        JdbcTemplate first = manager.getJdbcTemplate();
        JdbcTemplate second = manager.getJdbcTemplate();
        assertSame(first, second, "同一时刻只应存在一个池");

        manager.close();

        assertSame(first, manager.getJdbcTemplate(), "仍有借出者时不得重建或关闭连接池");
    }

    @Test
    void close_afterAllBorrowersReleased_rebuildsOnNextUse() {
        JdbcTemplate first = manager.getJdbcTemplate();

        manager.close();
        JdbcTemplate second = manager.getJdbcTemplate();

        assertNotSame(first, second, "全部归还后应按需重建连接池");
    }

    @Test
    void saveConfig_keepsOldPoolUntilLastBorrowerReleases() {
        JdbcTemplate before = manager.getJdbcTemplate();

        manager.saveConfig("db.example.com", "user", "pwd");

        assertSame(before, manager.getJdbcTemplate(), "配置变更时若还有借出者，应先继续用旧池");
        manager.close();
        manager.close();
        assertNotSame(before, manager.getJdbcTemplate(), "借出者清零后下一个请求应拿到按新配置建的池");
    }

    @Test
    void getConfig_masksStoredPassword() {
        Map<String, Object> config = manager.getConfig();

        assertEquals("******", config.get("password"));
        assertEquals("127.0.0.1", config.get("host"));
        assertEquals(3306, config.get("port"));
        assertEquals("Uniview", config.get("database"));
    }

    @Test
    void getConfig_emptyPassword_returnsBlankInsteadOfMask() {
        ReflectionTestUtils.setField(manager, "defaultPassword", "");

        assertEquals("", manager.getConfig().get("password"));
    }
}
