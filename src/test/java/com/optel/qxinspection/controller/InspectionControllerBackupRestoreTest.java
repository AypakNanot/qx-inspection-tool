package com.optel.qxinspection.controller;

import com.optel.qxinspection.service.AuditService;
import com.optel.qxinspection.service.ClockInspectionService;
import com.optel.qxinspection.service.InspectionScheduler;
import com.optel.qxinspection.service.InspectionService;
import com.optel.qxinspection.service.ThresholdService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 备份 / 恢复端点测试（真实 sqlite-jdbc，非 mock）。
 * <p>恢复是本工具风险最高的操作：它会整文件覆盖活库。这里覆盖三条底线——
 * 校验不过不许动活库、覆盖前必须留可回滚快照、旧库残留的 -wal/-shm 必须清掉
 * （否则下次连接会把旧事务回放到刚恢复的库上，数据就串了）。</p>
 */
class InspectionControllerBackupRestoreTest {

    private static final String ADMIN_TOKEN = "test-token";
    private static final String PRE_RESTORE_BACKUP = "qx_inspection_backup_before_restore.db";

    private static final String[] REQUIRED_DDL = {
            "CREATE TABLE IF NOT EXISTS link_inspection_result ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, round_id INTEGER NOT NULL, link_name TEXT)",
            "CREATE TABLE IF NOT EXISTS inspection_round ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, trigger_type TEXT NOT NULL, status TEXT)",
            "CREATE TABLE IF NOT EXISTS device_access_config ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, ne_id TEXT NOT NULL UNIQUE, ip_addr TEXT NOT NULL)"
    };

    @TempDir
    Path tempDir;

    private Path dbPath;
    private JdbcTemplate jdbc;
    private InspectionController controller;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("live.db");
        jdbc = jdbcFor(dbPath);
        createTablesAt(dbPath);

        controller = new InspectionController(
                mock(InspectionService.class), mock(InspectionScheduler.class), mock(AuditService.class),
                mock(ThresholdService.class), mock(ClockInspectionService.class), jdbc);
        ReflectionTestUtils.setField(controller, "adminToken", ADMIN_TOKEN);
        ReflectionTestUtils.setField(controller, "sqliteUrl", "jdbc:sqlite:" + fwd(dbPath));
    }

    // ========== 恢复 ==========

    @Test
    void restore_rejectsDatabaseMissingRequiredTables_andLeavesLiveDbUntouched() throws Exception {
        jdbc.update("INSERT INTO link_inspection_result (round_id, link_name) VALUES (1, 'LIVE-ONLY')");
        byte[] liveBefore = Files.readAllBytes(dbPath);
        Path foreign = createDbWith("CREATE TABLE unrelated (id INTEGER)");

        Map<String, Object> result = controller.restoreDatabase(authedRequest(), upload(foreign));

        assertEquals(false, result.get("success"));
        assertEquals("文件中缺少必需的数据表，不是本工具的数据库备份", result.get("message"));
        assertArrayEquals(liveBefore, Files.readAllBytes(dbPath), "校验失败时不得改动活库");
    }

    @Test
    void restore_validBackup_replacesLiveDatabase() throws Exception {
        jdbc.update("INSERT INTO link_inspection_result (round_id, link_name) VALUES (1, 'LIVE-ONLY')");
        Path backup = createDbWith(REQUIRED_DDL);
        insertLink(backup, "FROM-BACKUP");

        Map<String, Object> result = controller.restoreDatabase(authedRequest(), upload(backup));

        assertEquals(true, result.get("success"));
        assertEquals("FROM-BACKUP",
                jdbc.queryForObject("SELECT link_name FROM link_inspection_result", String.class));
    }

    @Test
    void restore_removesStaleWalAndShmSidecars() throws Exception {
        Path wal = dbPath.resolveSibling(dbPath.getFileName() + "-wal");
        Path shm = dbPath.resolveSibling(dbPath.getFileName() + "-shm");
        Files.writeString(wal, "stale");
        Files.writeString(shm, "stale");

        controller.restoreDatabase(authedRequest(), upload(createDbWith(REQUIRED_DDL)));

        assertFalse(Files.exists(wal), "旧库残留的 -wal 会在下次连接时回放，必须删除");
        assertFalse(Files.exists(shm), "旧库残留的 -shm 必须删除");
    }

    @Test
    void restore_keepsPreRestoreSnapshotOfCurrentDatabase() throws Exception {
        jdbc.update("INSERT INTO link_inspection_result (round_id, link_name) VALUES (1, 'LIVE-ONLY')");

        controller.restoreDatabase(authedRequest(), upload(createDbWith(REQUIRED_DDL)));

        Path prev = dbPath.resolveSibling(PRE_RESTORE_BACKUP);
        assertTrue(Files.exists(prev), "覆盖前应留存可回滚的快照");
        assertTrue(Files.size(prev) > 0);
    }

    @Test
    void restore_withoutAdminToken_isForbidden() throws Exception {
        Map<String, Object> result =
                controller.restoreDatabase(new MockHttpServletRequest(), upload(createDbWith(REQUIRED_DDL)));

        assertEquals(false, result.get("success"));
        assertEquals("需要管理员权限", result.get("message"));
    }

    @Test
    void restore_rejectsNonDotDbExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "backup.zip",
                "application/octet-stream", new byte[64]);

        assertEquals("仅支持 .db 文件", controller.restoreDatabase(authedRequest(), file).get("message"));
    }

    @Test
    void restore_rejectsFileWithBadMagicHeader() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.db",
                "application/octet-stream", "definitely not a sqlite file".getBytes(StandardCharsets.US_ASCII));

        String message = String.valueOf(controller.restoreDatabase(authedRequest(), file).get("message"));
        assertTrue(message.contains("SQLite"), "应识别出这不是 SQLite 文件，实际: " + message);
    }

    // ========== 备份 ==========

    @Test
    void backup_streamsAReadableSqliteSnapshot() throws Exception {
        jdbc.update("INSERT INTO link_inspection_result (round_id, link_name) VALUES (1, 'LIVE-ONLY')");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.backupDatabase(authedRequest(), response);

        byte[] body = response.getContentAsByteArray();
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertTrue(body.length > 0, "备份内容不能为空");
        assertEquals("SQLite format 3", new String(body, 0, 15, StandardCharsets.US_ASCII),
                "备份必须是一个完整的 SQLite 文件头");
    }

    @Test
    void backup_unauthenticated_returnsForbidden() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.backupDatabase(new MockHttpServletRequest(), response);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    // ========== 辅助 ==========

    private MockHttpServletRequest authedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Token", ADMIN_TOKEN);
        return request;
    }

    private MockMultipartFile upload(Path file) throws IOException {
        return new MockMultipartFile("file", file.getFileName().toString(),
                "application/octet-stream", Files.readAllBytes(file));
    }

    private static JdbcTemplate jdbcFor(Path dbFile) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + fwd(dbFile)));
    }

    private static void createTablesAt(Path dbFile) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fwd(dbFile));
             var stmt = conn.createStatement()) {
            for (String ddl : REQUIRED_DDL) {
                stmt.execute(ddl);
            }
        }
    }

    private Path createDbWith(String... ddl) throws Exception {
        Path file = tempDir.resolve("db_" + System.nanoTime() + ".db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fwd(file));
             var stmt = conn.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
        }
        return file;
    }

    private static void insertLink(Path dbFile, String linkName) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + fwd(dbFile));
             var stmt = conn.prepareStatement(
                     "INSERT INTO link_inspection_result (round_id, link_name) VALUES (1, ?)")) {
            stmt.setString(1, linkName);
            stmt.executeUpdate();
        }
    }

    private static String fwd(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
