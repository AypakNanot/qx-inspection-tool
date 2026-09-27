package com.optel.qxinspection.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
    }
}
