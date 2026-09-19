package com.optel.qxinspection.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 业务同步服务单元测试。
 * <p>重点覆盖 MySQL 源表列名映射：dmrelation(oid→reo)、defdmne(neType→cName)、
 * emnecomm(oid→ipAddr)、portbandwidth(dir=1)。列名写错会静默产出空数据。</p>
 */
@ExtendWith(MockitoExtension.class)
class DynamicSyncServiceTest {

    private static final String NET_71 = "71";
    private static final String NET_NAME_71 = "上饶南区_地调";
    private static final String NET_484 = "484";
    private static final String NET_NAME_484 = "吉安_地调";

    @Mock
    private JdbcTemplate sqliteJdbc;

    @Mock
    private MysqlConnectionManager mysqlConnectionManager;

    @Mock
    private JdbcTemplate mysqlJdbc;

    @Mock
    private PlatformTransactionManager sqliteTxManager;

    @Mock
    private TransactionStatus txStatus;

    private DynamicSyncService dynamicSyncService;

    @BeforeEach
    void setUp() {
        lenient().when(mysqlConnectionManager.getJdbcTemplate()).thenReturn(mysqlJdbc);
        lenient().when(sqliteTxManager.getTransaction(any())).thenReturn(txStatus);
        dynamicSyncService = new DynamicSyncService(sqliteJdbc, mysqlConnectionManager, sqliteTxManager);
    }

    // ========== 源表行构造 ==========

    private static Map<String, Object> dmeoRow(String oid, int cid, int type, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("oid", oid);
        row.put("cid", cid);
        row.put("type", type);
        row.put("name", name);
        row.put("defName", name);
        return row;
    }

    /** dmrelation 真实列只有 oid / reo / type：oid=子对象，reo=父对象 */
    private static Map<String, Object> relRow(String oid, String reo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("oid", oid);
        row.put("reo", reo);
        row.put("type", 1);
        return row;
    }

    private static Map<String, Object> defRow(int neType, String cName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("neType", neType);
        row.put("eName", cName);
        row.put("cName", cName);
        return row;
    }

    private static Map<String, Object> commRow(String oid, String ipAddr, int state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("oid", oid);
        row.put("ipAddr", ipAddr);
        row.put("state", state);
        return row;
    }

    private static Map<String, Object> bwRow(String oid, int dir, int capacity, int used) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("oid", oid);
        row.put("dir", dir);
        row.put("capacity", capacity);
        row.put("used", used);
        return row;
    }

    private static Map<String, Object> connRow(String oid, String name, String aEnd, String zEnd) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("oid", oid);
        row.put("cid", 100);
        row.put("name", name);
        row.put("aEnd", aEnd);
        row.put("zEnd", zEnd);
        row.put("createTime", 1766000000);
        row.put("creator", "root");
        row.put("additionInfo", null);
        return row;
    }

    /** 覆盖全部源表：71 下有 NE 101/102，484 下有 NE 1026 */
    private void stubSourceTables() {
        when(mysqlJdbc.queryForList("SELECT * FROM dmeo")).thenReturn(List.of(
                dmeoRow(NET_71, 1, 0, NET_NAME_71),
                dmeoRow(NET_484, 1, 0, NET_NAME_484),
                dmeoRow("101", 2, 6301, "NE101"),
                dmeoRow("102", 2, 6301, "NE102"),
                dmeoRow("1026", 2, 6302, "NE1026"),
                dmeoRow("101:1:11:1", 5, 0, "P1"),
                dmeoRow("102:1:11:2", 5, 0, "P2")));
        when(mysqlJdbc.queryForList("SELECT * FROM dmrelation")).thenReturn(List.of(
                relRow("101", NET_71),
                relRow("102", NET_71),
                relRow("1026", NET_484)));
        when(mysqlJdbc.queryForList("SELECT * FROM defdmne")).thenReturn(List.of(
                defRow(6301, "MatrixEdge2050"),
                defRow(6302, "MatrixEdge810")));
        when(mysqlJdbc.queryForList("SELECT * FROM emnecomm")).thenReturn(List.of(
                commRow("101", "195.12.3.1", 1),
                commRow("102", "195.12.4.1", 1),
                commRow("1026", "198.30.38.1", 0)));
        when(mysqlJdbc.queryForList("SELECT * FROM portbandwidth")).thenReturn(List.of(
                bwRow("101:1:11:1", 1, 1008, 21),
                bwRow("101:1:11:1", 2, 2222, 22),
                bwRow("101:1:11:1", 3, 3333, 33)));
        when(mysqlJdbc.queryForList("SELECT * FROM dmconnection WHERE cid = ?", 100)).thenReturn(List.of(
                connRow("9001", "L1", "101:1:11:1", "102:1:11:2"),
                connRow("9002", "L2", "1026:1:11:1", "101:1:11:1"),
                connRow("9003", "L3", "1026:1:11:1", "9999:1:11:1")));
    }

    /** 取出写入 SQLite 的行（按列序还原） */
    private static List<Object> readRow(BatchPreparedStatementSetter setter, int idx) throws SQLException {
        TreeMap<Integer, Object> captured = new TreeMap<>();
        PreparedStatement ps = mock(PreparedStatement.class, withSettings().lenient());
        Answer<Void> record = invocation -> {
            captured.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        };
        doAnswer(record).when(ps).setObject(anyInt(), any());
        doAnswer(invocation -> {
            captured.put(invocation.getArgument(0), null);
            return null;
        }).when(ps).setNull(anyInt(), anyInt());
        setter.setValues(ps, idx);
        return new ArrayList<>(captured.values());
    }

    private List<List<Object>> capturedRows(String insertSql) throws SQLException {
        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(sqliteJdbc).batchUpdate(eq(insertSql), captor.capture());
        BatchPreparedStatementSetter setter = captor.getValue();
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < setter.getBatchSize(); i++) {
            rows.add(readRow(setter, i));
        }
        return rows;
    }

    private static List<Object> rowByOid(List<List<Object>> rows, String oid) {
        return rows.stream()
                .filter(r -> oid.equals(r.get(0)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 oid=" + oid + " 的写入行: " + rows));
    }

    // ========== 正常同步 ==========

    @Test
    void testSyncNetworks_KeepsObjectsOfSelectedNetworkAndResolvesRedundantFields() throws SQLException {
        stubSourceTables();

        Map<String, Object> result = dynamicSyncService.syncNetworks(List.of(NET_71));

        // 71 下：网络自身 + NE101/N102 + 两个端口 = 5 行 dmeo, 其中 2 个 NE
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(2L, result.get("neCount"));
        assertEquals(2L, result.get("linkCount"));
        assertNotNull(result.get("portCount"));
        assertEquals(NET_NAME_71, result.get("networks"));

        List<List<Object>> dmeoRows = capturedRows(
                "INSERT INTO dmeo (oid, cid, type, name, defName, networkOid, networkName, neName, neTypeName, ipAddr) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        assertEquals(5, dmeoRows.size());

        // NE 行：网元名/网元类型(defdmne.neType 映射)/IP(emnecomm.oid 映射) 必须都填上
        List<Object> ne101 = rowByOid(dmeoRows, "101");
        assertEquals(2, ne101.get(1));
        assertEquals(6301, ne101.get(2));
        assertEquals("NE101", ne101.get(3));
        assertEquals(NET_71, ne101.get(5));
        assertEquals(NET_NAME_71, ne101.get(6));
        assertEquals("NE101", ne101.get(7));
        assertEquals("MatrixEdge2050", ne101.get(8));
        assertEquals("195.12.3.1", ne101.get(9));

        // 未选中网络的 NE 不应出现
        assertTrue(dmeoRows.stream().noneMatch(r -> "1026".equals(r.get(0))));
        assertTrue(dmeoRows.stream().noneMatch(r -> NET_484.equals(r.get(0))));
    }

    @Test
    void testSyncNetworks_LinkKeptWhenEitherEndNeSelected() throws SQLException {
        stubSourceTables();

        dynamicSyncService.syncNetworks(List.of(NET_71));

        List<List<Object>> connRows = capturedRows(
                "INSERT INTO dmconnection (oid, cid, name, aEnd, zEnd, createTime, creator, additionInfo, "
                        + "aNeName, aNeTypeName, aNetworkName, aPortName, aCapacity, aUsed, "
                        + "zNeName, zNeTypeName, zNetworkName, zPortName, zCapacity, zUsed) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        // 9001 双端在 71 内；9002 只有 Z 端在 71 内（跨网络链路保留）；9003 双端都不在
        assertEquals(2, connRows.size());
        assertTrue(connRows.stream().noneMatch(r -> "9003".equals(r.get(0))));

        List<Object> link = rowByOid(connRows, "9001");
        assertEquals("L1", link.get(2));
        assertEquals("NE101", link.get(8));
        assertEquals("MatrixEdge2050", link.get(9));
        assertEquals(NET_NAME_71, link.get(10));
        assertEquals("P1(1/11/1)", link.get(11));
        assertEquals("NE102", link.get(14));
        assertEquals("P2(1/11/2)", link.get(17));
    }

    @Test
    void testSyncNetworks_PortbandwidthTakesDirOne() throws SQLException {
        stubSourceTables();

        dynamicSyncService.syncNetworks(List.of(NET_71));

        List<List<Object>> connRows = capturedRows(
                "INSERT INTO dmconnection (oid, cid, name, aEnd, zEnd, createTime, creator, additionInfo, "
                        + "aNeName, aNeTypeName, aNetworkName, aPortName, aCapacity, aUsed, "
                        + "zNeName, zNeTypeName, zNetworkName, zPortName, zCapacity, zUsed) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        List<Object> link = rowByOid(connRows, "9001");
        // dir=1 的 1008/21，而不是 dir=2/3 的 2222/3333
        assertEquals(1008, link.get(12));
        assertEquals(21, link.get(13));
        // 无 portbandwidth 记录时回退 0
        assertEquals(0, link.get(18));
        assertEquals(0, link.get(19));
    }

    @Test
    void testSyncNetworks_EmnecommStateZeroLeavesIpNull() throws SQLException {
        stubSourceTables();

        dynamicSyncService.syncNetworks(List.of(NET_484));

        List<List<Object>> dmeoRows = capturedRows(
                "INSERT INTO dmeo (oid, cid, type, name, defName, networkOid, networkName, neName, neTypeName, ipAddr) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        assertEquals(2, dmeoRows.size());

        List<Object> ne1026 = rowByOid(dmeoRows, "1026");
        assertEquals(NET_NAME_484, ne1026.get(6));
        assertEquals("MatrixEdge810", ne1026.get(8));
        assertNull(ne1026.get(9));
    }

    @Test
    void testSyncNetworks_OverwritesSyncMetadata() {
        stubSourceTables();

        dynamicSyncService.syncNetworks(List.of(NET_71));

        verify(sqliteJdbc).update(anyString(), eq("sync.networkIds"), eq(NET_71));
        verify(sqliteJdbc).update(anyString(), eq("sync.networkNames"), eq(NET_NAME_71));
        verify(sqliteJdbc).update(anyString(), eq("sync.status"), eq("SUCCESS"));
    }

    // ========== 异常路径 ==========

    @Test
    void testSyncNetworks_EmptyNetworks_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> dynamicSyncService.syncNetworks(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> dynamicSyncService.syncNetworks(null));
    }

    @Test
    void testSyncNetworks_QueryFails_MarksFailedAndRethrows() {
        when(mysqlJdbc.queryForList(anyString()))
                .thenThrow(new IllegalStateException("MySQL 不可达"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dynamicSyncService.syncNetworks(List.of(NET_71)));

        assertEquals("MySQL 不可达", ex.getMessage());
        verify(sqliteJdbc).update(anyString(), eq("sync.status"), eq("FAILED"));
        verify(mysqlConnectionManager).close();
    }

    @Test
    void testSyncNetworks_ClosesMysqlConnectionOnSuccess() {
        stubSourceTables();

        dynamicSyncService.syncNetworks(List.of(NET_71));

        verify(mysqlConnectionManager).close();
    }
}
