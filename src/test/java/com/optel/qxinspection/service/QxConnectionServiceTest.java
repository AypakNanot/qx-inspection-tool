package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import com.optel.qxinspection.qx.QxDeviceServiceImpl;
import com.optel.qxinspection.reconnect.QxReconnectManager;
import com.optel.qxinspection.repository.sqlite.ConnProfileRepository;
import com.optel.qxinspection.repository.sqlite.DeviceAccessConfigRepository;
import com.optel.qx.cci.channel.QxChannelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QX连接服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class QxConnectionServiceTest {

    @Mock
    private QxDeviceServiceImpl qxDeviceService;

    @Mock
    private QxReconnectManager reconnectManager;

    @Mock
    private DeviceAccessConfigRepository deviceAccessConfigRepository;

    @Mock
    private ConnProfileRepository connProfileRepository;

    @Mock
    private QxChannelManager channelManager;

    @InjectMocks
    private QxConnectionService qxConnectionService;

    private DeviceAccessConfig testDevice;
    private ConnProfile globalProfile;

    @BeforeEach
    void setUp() {
        testDevice = new DeviceAccessConfig();
        testDevice.setId(1L);
        testDevice.setNeId("1.1");
        testDevice.setNeName("NE-001");
        testDevice.setIpAddr("192.168.1.100");
        testDevice.setPort(9900);
        testDevice.setConnectionStatus(0);

        globalProfile = new ConnProfile();
        globalProfile.setScope("GLOBAL");
        globalProfile.setNeOid("");
        globalProfile.setUsername("admin");
        globalProfile.setPassword("test123");
        globalProfile.setPort(9900);
    }

    // ========== isConnected ==========

    @Test
    void testIsConnected_NoChannelId() {
        // channelIdMap中无此设备 → 直接返回false
        boolean result = qxConnectionService.isConnected("1.1");
        assertFalse(result);
    }

    // ========== connectSingle ==========

    @Test
    void testConnectSingle_DeviceNotFound() {
        when(deviceAccessConfigRepository.findByNeId("99.99")).thenReturn(Optional.empty());

        Map<String, Object> result = qxConnectionService.connectSingle("99.99");

        assertFalse((Boolean) result.get("success"));
        assertEquals("设备配置不存在", result.get("message"));
    }

    @Test
    void testConnectSingle_NoGlobalProfile() {
        when(deviceAccessConfigRepository.findByNeId("1.1")).thenReturn(Optional.of(testDevice));
        when(connProfileRepository.findByScopeAndNeOid("GLOBAL", "")).thenReturn(Optional.empty());
        when(connProfileRepository.findByScopeAndNeOid("NE", "1.1")).thenReturn(Optional.empty());

        Map<String, Object> result = qxConnectionService.connectSingle("1.1");

        // 无全局配置也无设备配置 → buildChannelProp返回null
        assertFalse((Boolean) result.get("success"));
    }

    // ========== disconnectSingle ==========

    @Test
    void testDisconnectSingle_NotConnected() {
        // channelIdMap中无此设备
        Map<String, Object> result = qxConnectionService.disconnectSingle("1.1");

        assertFalse((Boolean) result.get("success"));
        assertEquals("设备未连接", result.get("message"));
    }

    // ========== connectAll ==========

    @Test
    void testConnectAll_EmptyDevices() {
        when(deviceAccessConfigRepository.findByEnabledTrue()).thenReturn(java.util.Collections.emptyList());
        when(connProfileRepository.findByScopeAndNeOid("GLOBAL", "")).thenReturn(Optional.of(globalProfile));

        Map<String, Object> result = qxConnectionService.connectAll(null);

        assertEquals(0, result.get("total"));
        assertEquals(0, result.get("success"));
    }

    // ========== disconnectAll ==========

    @Test
    void testDisconnectAll_NoConnections() {
        // channelIdMap为空
        Map<String, Object> result = qxConnectionService.disconnectAll(null);

        assertEquals(0, result.get("disconnected"));
    }

    // ========== getGlobalConfig ==========

    @Test
    void testGetGlobalConfig_Found() {
        when(connProfileRepository.findByScopeAndNeOid("GLOBAL", "")).thenReturn(Optional.of(globalProfile));

        Optional<ConnProfile> result = qxConnectionService.getGlobalConfig();

        assertTrue(result.isPresent());
        assertEquals("admin", result.get().getUsername());
        assertEquals(9900, result.get().getPort());
    }

    @Test
    void testGetGlobalConfig_NotFound() {
        when(connProfileRepository.findByScopeAndNeOid("GLOBAL", "")).thenReturn(Optional.empty());

        Optional<ConnProfile> result = qxConnectionService.getGlobalConfig();

        assertFalse(result.isPresent());
    }

    // ========== getDeviceConfig ==========

    @Test
    void testGetDeviceConfig_Found() {
        ConnProfile deviceProfile = new ConnProfile();
        deviceProfile.setScope("NE");
        deviceProfile.setNeOid("1.1");
        deviceProfile.setUsername("admin2");
        deviceProfile.setPassword("test456");
        deviceProfile.setPort(9901);

        when(connProfileRepository.findByScopeAndNeOid("NE", "1.1")).thenReturn(Optional.of(deviceProfile));

        Optional<ConnProfile> result = qxConnectionService.getDeviceConfig("1.1");

        assertTrue(result.isPresent());
        assertEquals("admin2", result.get().getUsername());
        assertEquals(9901, result.get().getPort());
    }

    @Test
    void testGetDeviceConfig_NotFound() {
        when(connProfileRepository.findByScopeAndNeOid("NE", "99.99")).thenReturn(Optional.empty());

        Optional<ConnProfile> result = qxConnectionService.getDeviceConfig("99.99");

        assertFalse(result.isPresent());
    }

    // ========== getPort ==========

    @Test
    void testGetPort_DeviceProfileOverridesGlobal() {
        ConnProfile deviceProfile = new ConnProfile();
        deviceProfile.setPort(9901);

        when(connProfileRepository.findByScopeAndNeOid("NE", "1.1")).thenReturn(Optional.of(deviceProfile));

        int port = qxConnectionService.getPort(testDevice, globalProfile);

        assertEquals(9901, port);
    }

    @Test
    void testGetPort_FallbackToGlobal() {
        when(connProfileRepository.findByScopeAndNeOid("NE", "1.1")).thenReturn(Optional.empty());

        int port = qxConnectionService.getPort(testDevice, globalProfile);

        assertEquals(9900, port);
    }

    @Test
    void testGetPort_FallbackToDefault() {
        when(connProfileRepository.findByScopeAndNeOid("NE", "1.1")).thenReturn(Optional.empty());

        int port = qxConnectionService.getPort(testDevice, null);

        assertEquals(9900, port);
    }

    // ========== saveGlobalConfig ==========

    @Test
    void testSaveGlobalConfig_NewProfile() {
        when(connProfileRepository.findByScopeAndNeOid("GLOBAL", "")).thenReturn(Optional.empty());
        when(connProfileRepository.save(any(ConnProfile.class))).thenAnswer(i -> i.getArgument(0));

        ConnProfile result = qxConnectionService.saveGlobalConfig("admin", "test123", 9900);

        assertNotNull(result);
        assertEquals("GLOBAL", result.getScope());
        assertEquals("admin", result.getUsername());
        assertEquals(9900, result.getPort());
        verify(connProfileRepository).save(any(ConnProfile.class));
    }

    @Test
    void testSaveGlobalConfig_UpdateExisting() {
        when(connProfileRepository.findByScopeAndNeOid("GLOBAL", "")).thenReturn(Optional.of(globalProfile));
        when(connProfileRepository.save(any(ConnProfile.class))).thenAnswer(i -> i.getArgument(0));

        ConnProfile result = qxConnectionService.saveGlobalConfig("newuser", "newpass", 9901);

        assertEquals("newuser", result.getUsername());
        assertEquals(9901, result.getPort());
    }
}
