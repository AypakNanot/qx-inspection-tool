package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.entity.sqlite.InspectionRound;
import com.optel.qxinspection.laser.service.ILaserService;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import com.optel.qxinspection.repository.sqlite.InspectionRoundRepository;
import com.optel.qxinspection.repository.sqlite.OpticalPowerInspectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 巡检服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class InspectionServiceTest {

    @Mock
    private ILaserService laserService;

    @Mock
    private QxConnectionService qxConnectionService;

    @Mock
    private DeviceAccessConfigRepository deviceAccessConfigRepository;

    @Mock
    private InspectionRoundRepository inspectionRoundRepository;

    @Mock
    private OpticalPowerInspectionRepository powerRecordRepository;

    @Mock
    private JdbcTemplate sqliteJdbc;

    @Mock
    private ThresholdService thresholdService;

    @Mock
    private SysConfigService sysConfigService;

    @InjectMocks
    private InspectionService inspectionService;

    private DeviceAccessConfig testDevice;

    @BeforeEach
    void setUp() {
        testDevice = new DeviceAccessConfig();
        testDevice.setId(1L);
        testDevice.setNeId("1.1");
        testDevice.setNeName("NE-001");
        testDevice.setIpAddr("192.168.1.100");
        testDevice.setPort(9900);
        testDevice.setConnectionStatus(1);
    }

    // ========== triggerInspectionAll ==========

    @Test
    void testTriggerInspectionAll_Success() {
        lenient().when(sysConfigService.get(eq("collect.concurrency"), anyString())).thenReturn("10");
        lenient().when(sysConfigService.get(eq("collect.maxRounds"), anyString())).thenReturn("10");
        lenient().when(sysConfigService.get(eq("inspect.autoConnect"), anyString())).thenReturn("true");
        lenient().when(sysConfigService.get(eq("inspect.autoDisconnect"), anyString())).thenReturn("true");
        lenient().when(sysConfigService.get(eq("inspect.saveInvalid"), anyString())).thenReturn("true");

        List<DeviceAccessConfig> devices = Collections.singletonList(testDevice);

        when(deviceAccessConfigRepository.findAll()).thenReturn(devices);
        lenient().when(qxConnectionService.isConnected("1.1")).thenReturn(true);
        lenient().when(inspectionRoundRepository.save(any(InspectionRound.class))).thenAnswer(invocation -> {
            InspectionRound round = invocation.getArgument(0);
            if (round.getId() == null) round.setId(1L);
            return round;
        });

        InspectionRound result = inspectionService.triggerInspectionAll();

        assertNotNull(result);
        assertEquals("MANUAL", result.getTriggerType());
        assertEquals("ALL", result.getScopeType());
        verify(deviceAccessConfigRepository).findAll();
        verify(inspectionRoundRepository, atLeast(1)).save(any(InspectionRound.class));
    }

    // ========== triggerInspectionByNe ==========

    @Test
    void testTriggerInspectionByNe_Success() {
        lenient().when(sysConfigService.get(eq("collect.concurrency"), anyString())).thenReturn("10");
        lenient().when(sysConfigService.get(eq("collect.maxRounds"), anyString())).thenReturn("10");
        lenient().when(sysConfigService.get(eq("inspect.autoConnect"), anyString())).thenReturn("true");
        lenient().when(sysConfigService.get(eq("inspect.autoDisconnect"), anyString())).thenReturn("true");
        lenient().when(sysConfigService.get(eq("inspect.saveInvalid"), anyString())).thenReturn("true");

        when(deviceAccessConfigRepository.findAll()).thenReturn(Collections.singletonList(testDevice));
        lenient().when(qxConnectionService.isConnected("1.1")).thenReturn(true);
        lenient().when(inspectionRoundRepository.save(any(InspectionRound.class))).thenAnswer(invocation -> {
            InspectionRound round = invocation.getArgument(0);
            if (round.getId() == null) round.setId(2L);
            return round;
        });

        InspectionRound result = inspectionService.triggerInspectionByNe("1.1");

        assertNotNull(result);
        assertEquals("SINGLE", result.getScopeType());
        verify(inspectionRoundRepository, atLeast(1)).save(any(InspectionRound.class));
    }

    // ========== triggerInspectionByNetwork ==========

    @Test
    void testTriggerInspectionByNetwork_Success() {
        lenient().when(sysConfigService.get(eq("collect.concurrency"), anyString())).thenReturn("10");
        lenient().when(sysConfigService.get(eq("collect.maxRounds"), anyString())).thenReturn("10");
        lenient().when(sysConfigService.get(eq("inspect.autoConnect"), anyString())).thenReturn("true");
        lenient().when(sysConfigService.get(eq("inspect.autoDisconnect"), anyString())).thenReturn("true");
        lenient().when(sysConfigService.get(eq("inspect.saveInvalid"), anyString())).thenReturn("true");

        when(deviceAccessConfigRepository.findAll()).thenReturn(Collections.singletonList(testDevice));
        lenient().when(qxConnectionService.isConnected("1.1")).thenReturn(true);
        lenient().when(inspectionRoundRepository.save(any(InspectionRound.class))).thenAnswer(invocation -> {
            InspectionRound round = invocation.getArgument(0);
            if (round.getId() == null) round.setId(3L);
            return round;
        });

        InspectionRound result = inspectionService.triggerInspectionByNetwork("骨干网A");

        assertNotNull(result);
        assertEquals("NETWORK", result.getScopeType());
        assertEquals("骨干网A", result.getScopeParam());
    }

    // ========== getProgress ==========

    @Test
    void testGetProgress_NoRunningInspection() {
        Map<String, Object> result = inspectionService.getProgress();

        assertNotNull(result);
        assertFalse((Boolean) result.get("running"));
    }

    // ========== listRounds ==========

    @Test
    void testListRounds_Empty() {
        when(inspectionRoundRepository.findAll()).thenReturn(Collections.emptyList());

        List<InspectionRound> result = inspectionService.listRounds();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testListRounds_WithRecords() {
        InspectionRound round1 = new InspectionRound();
        round1.setId(1L);
        round1.setStatus("COMPLETED");

        InspectionRound round2 = new InspectionRound();
        round2.setId(2L);
        round2.setStatus("RUNNING");

        when(inspectionRoundRepository.findAll()).thenReturn(Arrays.asList(round2, round1));

        List<InspectionRound> result = inspectionService.listRounds();

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    // ========== getSummary ==========

    @Test
    void testGetSummary_NoData() {
        when(inspectionRoundRepository.findFirstByOrderByStartTimeDesc()).thenReturn(Optional.empty());

        Map<String, Object> result = inspectionService.getSummary();

        assertNotNull(result);
        assertFalse((Boolean) result.get("hasData"));
    }

    // ========== getCollectParams ==========

    @Test
    void testGetCollectParams() {
        Map<String, Object> result = inspectionService.getCollectParams();

        assertNotNull(result);
        assertTrue(result.containsKey("concurrency"));
        assertTrue(result.containsKey("maxRounds"));
        assertTrue(result.containsKey("autoConnect"));
        assertTrue(result.containsKey("autoDisconnect"));
        assertTrue(result.containsKey("saveInvalid"));
    }
}
