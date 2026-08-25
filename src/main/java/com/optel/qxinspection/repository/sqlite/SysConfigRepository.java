package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysConfigRepository extends JpaRepository<SysConfig, String> {
    Optional<SysConfig> findByConfigKey(String configKey);
}
