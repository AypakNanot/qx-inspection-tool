package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.repository.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 设备同步服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class DeviceAccessServiceTest {

    @Mock
    private DataSource sqliteDataSource;

    @Mock
    private JdbcTemplate sqliteJdbc;

    @Mock
    private DeviceAccessConfigRepository deviceAccessConfigRepository;

    @Mock
    private ConnProfileRepository connProfileRepository;

    @Mock
    private InspectionRoundRepository inspectionRoundRepository;

    @Mock
    private OpticalPowerInspectionRepository opticalPowerInspectionRepository;

    @Mock
    private ThresholdRuleRepository thresholdRuleRepository;

    private DeviceAccessService deviceAccessService;

    @BeforeEach
    void setUp() {
        // 手动构造DeviceAccessService（因为构造函数需要DataSource创建JdbcTemplate）
        deviceAccessService = new DeviceAccessService(
                sqliteDataSource,
                deviceAccessConfigRepository,
                connProfileRepository,
                inspectionRoundRepository,
                opticalPowerInspectionRepository,
                thresholdRuleRepository
        );
        // 用反射注入mock的JdbcTemplate
        try {
            var field = DeviceAccessService.class.getDeclaredField("sqliteJdbc");
            field.setAccessible(true);
            field.set(deviceAccessService, sqliteJdbc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ========== getAllDeviceConfigs ==========

    @Test
    void testGetAllDeviceConfigs_Empty() {
        when(deviceAccessConfigRepository.findAll()).thenReturn(Collections.emptyList());

        List<DeviceAccessConfig> result = deviceAccessService.getAllDeviceConfigs();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(deviceAccessConfigRepository).findAll();
    }

    @Test
    void testGetAllDeviceConfigs_WithData() {
        DeviceAccessConfig device1 = new DeviceAccessConfig();
        device1.setNeId("1.1");
        device1.setNeName("NE-001");

        DeviceAccessConfig device2 = new DeviceAccessConfig();
        device2.setNeId("1.2");
        device2.setNeName("NE-002");

        when(deviceAccessConfigRepository.findAll()).thenReturn(Arrays.asList(device1, device2));

        List<DeviceAccessConfig> result = deviceAccessService.getAllDeviceConfigs();

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    // ========== getEnabledDeviceConfigs ==========

    @Test
    void testGetEnabledDeviceConfigs() {
        DeviceAccessConfig device = new DeviceAccessConfig();
        device.setNeId("1.1");
        device.setEnabled(true);

        when(deviceAccessConfigRepository.findByEnabledTrue()).thenReturn(Collections.singletonList(device));

        List<DeviceAccessConfig> result = deviceAccessService.getEnabledDeviceConfigs();

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(deviceAccessConfigRepository).findByEnabledTrue();
    }

    // ========== getAllNetworkNames ==========

    @Test
    void testGetAllNetworkNames_Empty() {
        when(deviceAccessConfigRepository.findAll()).thenReturn(Collections.emptyList());

        List<String> result = deviceAccessService.getAllNetworkNames();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetAllNetworkNames_WithDuplicates() {
        DeviceAccessConfig d1 = new DeviceAccessConfig();
        d1.setNetworkName("骨干网A");
        DeviceAccessConfig d2 = new DeviceAccessConfig();
        d2.setNetworkName("骨干网A");
        DeviceAccessConfig d3 = new DeviceAccessConfig();
        d3.setNetworkName("骨干网B");

        when(deviceAccessConfigRepository.findAll()).thenReturn(Arrays.asList(d1, d2, d3));

        List<String> result = deviceAccessService.getAllNetworkNames();

        assertEquals(2, result.size());
        assertTrue(result.contains("骨干网A"));
        assertTrue(result.contains("骨干网B"));
    }

    @Test
    void testGetAllNetworkNames_FilterEmpty() {
        DeviceAccessConfig d1 = new DeviceAccessConfig();
        d1.setNetworkName("");
        DeviceAccessConfig d2 = new DeviceAccessConfig();
        d2.setNetworkName(null);

        when(deviceAccessConfigRepository.findAll()).thenReturn(Arrays.asList(d1, d2));

        List<String> result = deviceAccessService.getAllNetworkNames();

        assertTrue(result.isEmpty());
    }

    // ========== testSQLiteConnection ==========

    @Test
    void testTestSQLiteConnection_Success() {
        when(deviceAccessConfigRepository.count()).thenReturn(10L);

        boolean result = deviceAccessService.testSQLiteConnection();

        assertTrue(result);
        verify(deviceAccessConfigRepository).count();
    }

    @Test
    void testTestSQLiteConnection_Failure() {
        when(deviceAccessConfigRepository.count()).thenThrow(new RuntimeException("DB error"));

        boolean result = deviceAccessService.testSQLiteConnection();

        assertFalse(result);
    }

    // ========== clearSelectedData ==========

    @Test
    void testClearSelectedData_InspectionRecords() {
        when(opticalPowerInspectionRepository.count()).thenReturn(100L);

        Map<String, Object> options = new HashMap<>();
        options.put("inspectionRecords", true);

        Map<String, Long> result = deviceAccessService.clearSelectedData(options);

        assertNotNull(result);
        assertEquals(100L, result.get("巡检记录"));
        verify(opticalPowerInspectionRepository).deleteAllInBatch();
    }

    @Test
    void testClearSelectedData_InspectionRounds() {
        when(inspectionRoundRepository.count()).thenReturn(5L);

        Map<String, Object> options = new HashMap<>();
        options.put("inspectionRounds", true);

        Map<String, Long> result = deviceAccessService.clearSelectedData(options);

        assertEquals(5L, result.get("巡检轮次"));
        verify(inspectionRoundRepository).deleteAllInBatch();
    }

    @Test
    void testClearSelectedData_ThresholdRules() {
        when(thresholdRuleRepository.count()).thenReturn(3L);

        Map<String, Object> options = new HashMap<>();
        options.put("thresholdRules", true);

        Map<String, Long> result = deviceAccessService.clearSelectedData(options);

        assertEquals(3L, result.get("门限规则"));
        verify(thresholdRuleRepository).deleteAllInBatch();
    }

    @Test
    void testClearSelectedData_Nothing() {
        Map<String, Object> options = new HashMap<>();

        Map<String, Long> result = deviceAccessService.clearSelectedData(options);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(opticalPowerInspectionRepository, never()).deleteAllInBatch();
        verify(inspectionRoundRepository, never()).deleteAllInBatch();
    }
}
