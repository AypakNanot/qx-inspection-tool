package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.SysConfig;
import com.optel.qxinspection.repository.sqlite.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统配置服务（SQLite持久化）
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigRepository sysConfigRepository;

    public String get(String key, String defaultValue) {
        return sysConfigRepository.findByConfigKey(key)
                .map(SysConfig::getConfigValue)
                .orElse(defaultValue);
    }

    @Transactional(transactionManager = "sqliteTransactionManager")
    public void set(String key, String value) {
        SysConfig config = sysConfigRepository.findByConfigKey(key).orElse(new SysConfig());
        config.setConfigKey(key);
        config.setConfigValue(value);
        sysConfigRepository.save(config);
    }
}
