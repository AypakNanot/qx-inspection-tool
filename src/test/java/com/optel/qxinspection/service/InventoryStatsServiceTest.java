package com.optel.qxinspection.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存统计服务单元测试 —— 聚焦类型统计的 typeName 分组与回退口径。
 */
@ExtendWith(MockitoExtension.class)
class InventoryStatsServiceTest {

    @Mock
    private JdbcTemplate sqliteJdbc;

    @InjectMocks
    private InventoryStatsService inventoryStatsService;

    @SuppressWarnings("unchecked")
    private void stubDmeoRows(Map<String, Object>... rows) {
        when(sqliteJdbc.queryForList(contains("FROM dmeo"), any(Object[].class)))
                .thenReturn(List.of(rows));
    }

    private static List<Map<String, Object>> byTypeName(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("byTypeName");
    }

    @Test
    void getPortStats_GroupsByTypeName() {
        stubDmeoRows(
                Map.of("type", 20008, "typeName", "STM4_O"),
                Map.of("type", 20008, "typeName", "STM4_O"),
                Map.of("type", 20009, "typeName", "STM16_O"));

        List<Map<String, Object>> list = byTypeName(inventoryStatsService.getPortStats(null));

        assertEquals(2, list.size());
        assertEquals("STM4_O", list.get(0).get("name"));
        assertEquals(2L, list.get(0).get("count"));
        assertEquals("STM16_O", list.get(1).get("name"));
        assertEquals(1L, list.get(1).get("count"));
    }

    @Test
    void getSlotStats_GroupsByTypeName() {
        stubDmeoRows(Map.of("type", 2001, "typeName", "MCP"));

        List<Map<String, Object>> list = byTypeName(inventoryStatsService.getSlotStats(null));

        assertEquals(1, list.size());
        assertEquals("MCP", list.get(0).get("name"));
        assertEquals(1L, list.get(0).get("count"));
    }

    @Test
    void getPortStats_FallsBackToTypeCodeWhenTypeNameMissing() {
        // typeName 缺失（未重新同步）或空串 → 各自回退原始 type 编码，与有名标签独立分组
        stubDmeoRows(
                Map.of("type", 20008),
                Map.of("type", 20008),
                Map.of("type", 20010, "typeName", ""),
                Map.of("type", 20010, "typeName", "10M_F_PORT"));

        List<Map<String, Object>> list = byTypeName(inventoryStatsService.getPortStats(null));

        assertEquals(3, list.size());
        assertEquals("20008", list.get(0).get("name"));
        assertEquals(2L, list.get(0).get("count"));
        // 空串回退为编码 "20010"；有名称的行独立成 "10M_F_PORT"（两者 count 相同，顺序为插入序）
        assertTrue(list.stream().anyMatch(e -> "20010".equals(e.get("name")) && e.get("count").equals(1L)));
        assertTrue(list.stream().anyMatch(e -> "10M_F_PORT".equals(e.get("name")) && e.get("count").equals(1L)));
    }

    @Test
    void getNeStats_Unchanged_GroupsByNeTypeName() {
        when(sqliteJdbc.queryForList(contains("neTypeName"), any(Object[].class)))
                .thenReturn(List.of(Map.of("neTypeName", "MatrixEdge2050")));

        List<Map<String, Object>> list =
                (List<Map<String, Object>>) inventoryStatsService.getNeStats(null).get("byNeTypeName");

        assertEquals(1, list.size());
        assertEquals("MatrixEdge2050", list.get(0).get("name"));

        // 网元统计不带空槽位过滤（EMPTY_PLACEHOLDER_TYPES 无 cid=2）
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(sqliteJdbc).queryForList(sqlCaptor.capture(), any(Object[].class));
        assertFalse(sqlCaptor.getValue().contains("type NOT IN"));
    }

    @Test
    void getSlotStats_SqlExcludesEmptyPlaceholderTypes() {
        // 桩只在 SQL 含 type NOT IN 时命中——实现回退则 queryForList 返回 null → NPE 红灯
        when(sqliteJdbc.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM dmeo") && sql.contains("type NOT IN")),
                any(Object[].class)))
                .thenReturn(List.of(Map.of("type", 2007, "typeName", "STM_1/4B")));

        List<Map<String, Object>> list = byTypeName(inventoryStatsService.getSlotStats(null));

        assertEquals(1, list.size());
        assertEquals("STM_1/4B", list.get(0).get("name"));
    }

    @Test
    void getOverview_ExcludesPlaceholdersFromSlotAndPortCounts() {
        // 网络/网元：无 NOT IN 过滤
        when(sqliteJdbc.queryForObject(
                argThat((String sql) -> sql != null && sql.contains("COUNT(*) FROM dmeo") && !sql.contains("type NOT IN")),
                eq(Long.class), eq(1))).thenReturn(3L);
        when(sqliteJdbc.queryForObject(
                argThat((String sql) -> sql != null && sql.contains("COUNT(*) FROM dmeo") && !sql.contains("type NOT IN")),
                eq(Long.class), eq(2))).thenReturn(10L);
        // 盘/端口：带 NOT IN（参数值钉死排除的占位类型）
        when(sqliteJdbc.queryForObject(contains("COUNT(*) FROM dmeo"),
                eq(Long.class), eq(4), eq(2000), eq(5079))).thenReturn(21636L);
        when(sqliteJdbc.queryForObject(contains("COUNT(*) FROM dmeo"),
                eq(Long.class), eq(5), eq(20000), eq(50100))).thenReturn(174082L);
        // 链路/需巡检端口：口径不变
        when(sqliteJdbc.queryForObject(contains("FROM dmconnection WHERE cid"),
                eq(Long.class), eq(100))).thenReturn(5L);
        when(sqliteJdbc.queryForObject(contains("DISTINCT port"), eq(Long.class))).thenReturn(7L);

        Map<String, Object> overview = inventoryStatsService.getOverview();

        assertEquals(3L, overview.get("networkCount"));
        assertEquals(10L, overview.get("neCount"));
        assertEquals(21636L, overview.get("slotCount"));
        assertEquals(174082L, overview.get("portCount"));
        assertEquals(5L, overview.get("linkCount"));
        assertEquals(7L, overview.get("inspectionPortCount"));
    }
}
