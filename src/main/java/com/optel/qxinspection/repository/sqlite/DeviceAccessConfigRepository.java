package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.DeviceAccessConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceAccessConfigRepository extends JpaRepository<DeviceAccessConfig, Long> {

    Optional<DeviceAccessConfig> findByNeId(String neId);

    List<DeviceAccessConfig> findByNeIdIn(List<String> neIds);

    List<DeviceAccessConfig> findByEnabled(Boolean enabled);

    List<DeviceAccessConfig> findByEnabledTrue();

    List<DeviceAccessConfig> findByConnectionStatus(Integer connectionStatus);

    List<DeviceAccessConfig> findByConnectionStatusAndEnabledTrue(Integer connectionStatus);
}
