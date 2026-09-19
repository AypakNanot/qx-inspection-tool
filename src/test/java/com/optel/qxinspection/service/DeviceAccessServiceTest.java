package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.sqlite.ConnProfileRepository;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import com.optel.qxinspection.repository.sqlite.AuditLogRepository;
import com.optel.qxinspection.repository.sqlite.InspectionRoundRepository;
import com.optel.qxinspection.repository.sqlite.LinkInspectionResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备同步服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class DeviceAccessServiceTest {

    @Mock
    private JdbcTemplate sqliteJdbc;

    @Mock
    private DeviceAccessConfigRepository deviceAccessConfigRepository;

    @Mock
    private ConnProfileRepository connProfileRepository;

    @Mock
    private InspectionRoundRepository inspectionRoundRepository;

    @Mock
    private LinkInspectionResultRepository linkResultRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private DeviceAccessService deviceAccessService;

    @BeforeEach
    void setUp() {
        deviceAccessService = new DeviceAccessService(sqliteJdbc,
                deviceAccessConfigRepository, connProfileRepository,
                inspectionRoundRepository, linkResultRepository, auditLogRepository);
    }

    private static Map<String, Object> neRow(String oid, String name, String ipAddr) {
        Map<String, Object> row = new HashMap<>();
        row.put("oid", oid);
        row.put("name", name);
        row.put("neTypeName", "MatrixEdge1830");
        row.put("networkName", "骨干网A");
        row.put("ipAddr", ipAddr);
        return row;
    }

    // ========== 查询 ==========

    @Test
    void testGetAllDeviceConfigs_Empty() {
        when(deviceAccessConfigRepository.findAll()).thenReturn(Collections.emptyList());

        assertTrue(deviceAccessService.getAllDeviceConfigs().isEmpty());
    }

    @Test
    void testGetAllDeviceConfigs_WithData() {
        DeviceAccessConfig device1 = new DeviceAccessConfig();
        device1.setNeId("1.1");
        DeviceAccessConfig device2 = new DeviceAccessConfig();
        device2.setNeId("1.2");
        when(deviceAccessConfigRepository.findAll()).thenReturn(List.of(device1, device2));

        assertEquals(2, deviceAccessService.getAllDeviceConfigs().size());
    }

    @Test
    void testGetEnabledDeviceConfigs() {
        DeviceAccessConfig device = new DeviceAccessConfig();
        device.setNeId("1.1");
        device.setEnabled(true);
        when(deviceAccessConfigRepository.findByEnabledTrue()).thenReturn(Collections.singletonList(device));

        assertEquals(1, deviceAccessService.getEnabledDeviceConfigs().size());
        verify(deviceAccessConfigRepository).findByEnabledTrue();
    }

    @Test
    void testTestSQLiteConnection_Success() {
        when(deviceAccessConfigRepository.count()).thenReturn(10L);

        assertTrue(deviceAccessService.testSQLiteConnection());
    }

    @Test
    void testTestSQLiteConnection_Failure() {
        when(deviceAccessConfigRepository.count()).thenThrow(new RuntimeException("DB error"));

        assertFalse(deviceAccessService.testSQLiteConnection());
    }

    // ========== syncDevicesFromSQLite ==========

    @Test
    void testSyncDevices_EmptyDmeo_Throws() {
        when(sqliteJdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> deviceAccessService.syncDevicesFromSQLite());
        assertTrue(ex.getMessage().contains("设备数据尚未同步"));
    }

    @Test
    void testSyncDevices_NullCount_Throws() {
        when(sqliteJdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> deviceAccessService.syncDevicesFromSQLite());
    }

    @Test
    void testSyncDevices_UpdatesExistingAndInsertsNew() {
        when(sqliteJdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);

        DeviceAccessConfig existing = new DeviceAccessConfig();
        existing.setNeId("1.1");
        existing.setNeName("旧名称");
        when(deviceAccessConfigRepository.findAll()).thenReturn(new ArrayList<>(List.of(existing)));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(neRow("1.1", "NE-001", "192.168.1.1"));
        rows.add(neRow("1.2", "NE-002", "192.168.1.2"));
        rows.add(neRow("1.3", "NE-003", "0.0.0.0"));
        rows.add(neRow("1.4", "NE-004", null));
        when(sqliteJdbc.queryForList(anyString())).thenReturn(rows);

        deviceAccessService.syncDevicesFromSQLite();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeviceAccessConfig>> captor = ArgumentCaptor.forClass(List.class);
        verify(deviceAccessConfigRepository).saveAll(captor.capture());

        List<DeviceAccessConfig> saved = captor.getValue();
        assertEquals(2, saved.size());

        assertEquals("NE-001", saved.get(0).getNeName());
        assertEquals("192.168.1.1", saved.get(0).getIpAddr());
        assertEquals("MatrixEdge1830", saved.get(0).getNeTypeName());

        DeviceAccessConfig inserted = saved.get(1);
        assertEquals("1.2", inserted.getNeId());
        assertEquals("NE-002", inserted.getNeName());
        assertTrue(inserted.getEnabled());
        assertEquals(0, inserted.getConnectionStatus().intValue());
    }

    @Test
    void testSyncDevices_FallbackNamesWhenMissing() {
        when(sqliteJdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(deviceAccessConfigRepository.findAll()).thenReturn(Collections.emptyList());

        Map<String, Object> row = new HashMap<>();
        row.put("oid", "9.9");
        row.put("name", null);
        row.put("neTypeName", null);
        row.put("networkName", null);
        row.put("ipAddr", "10.0.0.1");
        when(sqliteJdbc.queryForList(anyString())).thenReturn(List.of(row));

        deviceAccessService.syncDevicesFromSQLite();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeviceAccessConfig>> captor = ArgumentCaptor.forClass(List.class);
        verify(deviceAccessConfigRepository).saveAll(captor.capture());

        DeviceAccessConfig saved = captor.getValue().get(0);
        assertEquals("9.9", saved.getNeName());
        assertEquals("未知", saved.getNeTypeName());
        assertEquals("", saved.getNetworkName());
    }

    // ========== clearSelectedData ==========

    @Test
    void testClearSelectedData_InspectionRecords() {
        when(linkResultRepository.count()).thenReturn(100L);

        Map<String, Long> result = deviceAccessService.clearSelectedData(Map.of("inspectionRecords", true));

        assertEquals(100L, result.get("巡检记录"));
        verify(linkResultRepository).deleteAllInBatch();
    }

    @Test
    void testClearSelectedData_InspectionRounds() {
        when(inspectionRoundRepository.count()).thenReturn(5L);

        Map<String, Long> result = deviceAccessService.clearSelectedData(Map.of("inspectionRounds", true));

        assertEquals(5L, result.get("巡检轮次"));
        verify(inspectionRoundRepository).deleteAllInBatch();
    }

    @Test
    void testClearSelectedData_DeviceConfigs() {
        when(deviceAccessConfigRepository.count()).thenReturn(7L);

        Map<String, Long> result = deviceAccessService.clearSelectedData(Map.of("deviceConfigs", true));

        assertEquals(7L, result.get("设备配置"));
        verify(deviceAccessConfigRepository).deleteAllInBatch();
    }

    @Test
    void testClearSelectedData_AllConnectionProfiles() {
        when(connProfileRepository.count()).thenReturn(4L);

        Map<String, Long> result = deviceAccessService.clearSelectedData(Map.of("connectionProfiles", "all"));

        assertEquals(4L, result.get("连接配置"));
        verify(connProfileRepository).deleteAllInBatch();
    }

    @Test
    void testClearSelectedData_ConnectionProfilesByNetwork() {
        DeviceAccessConfig device = new DeviceAccessConfig();
        device.setNeId("1.1");
        device.setNetworkName("骨干网A");
        when(deviceAccessConfigRepository.findAll()).thenReturn(List.of(device));

        ConnProfile matched = new ConnProfile();
        matched.setScope("NE");
        matched.setNeOid("1.1");
        ConnProfile other = new ConnProfile();
        other.setScope("NE");
        other.setNeOid("9.9");
        ConnProfile global = new ConnProfile();
        global.setScope("GLOBAL");
        global.setNeOid("1.1");
        when(connProfileRepository.findAll()).thenReturn(List.of(matched, other, global));

        Map<String, Long> result = deviceAccessService.clearSelectedData(
                Map.of("connectionProfiles", List.of("骨干网A")));

        assertEquals(1L, result.get("连接配置(骨干网A)"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConnProfile>> captor = ArgumentCaptor.forClass(List.class);
        verify(connProfileRepository).deleteAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("1.1", captor.getValue().get(0).getNeOid());
    }

    @Test
    void testClearSelectedData_ConnectionProfilesByNetwork_NoMatch() {
        DeviceAccessConfig device = new DeviceAccessConfig();
        device.setNeId("1.1");
        device.setNetworkName("骨干网B");
        when(deviceAccessConfigRepository.findAll()).thenReturn(List.of(device));

        Map<String, Long> result = deviceAccessService.clearSelectedData(
                Map.of("connectionProfiles", List.of("骨干网A")));

        assertTrue(result.isEmpty());
        verify(connProfileRepository, never()).deleteAll(any());
    }

    @Test
    void testClearSelectedData_ConnectionProfilesEmptyListIgnored() {
        Map<String, Long> result = deviceAccessService.clearSelectedData(
                Map.of("connectionProfiles", Collections.emptyList()));

        assertTrue(result.isEmpty());
    }

    @Test
    void testClearSelectedData_ConnectionProfilesNullDeviceNetwork() {
        DeviceAccessConfig device = new DeviceAccessConfig();
        device.setNeId("1.1");
        device.setNetworkName(null);
        when(deviceAccessConfigRepository.findAll()).thenReturn(List.of(device));

        Map<String, Long> result = deviceAccessService.clearSelectedData(
                Map.of("connectionProfiles", List.of("骨干网A")));

        assertTrue(result.isEmpty());
        verify(connProfileRepository, never()).deleteAll(any());
    }

    @Test
    void testClearSelectedData_Nothing() {
        Map<String, Long> result = deviceAccessService.clearSelectedData(new LinkedHashMap<>());

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(linkResultRepository, never()).deleteAllInBatch();
        verify(inspectionRoundRepository, never()).deleteAllInBatch();
        verify(deviceAccessConfigRepository, never()).deleteAllInBatch();
        verify(connProfileRepository, never()).deleteAllInBatch();
    }
}
