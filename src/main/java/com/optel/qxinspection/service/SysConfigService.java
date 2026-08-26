package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.SysConfig;
import com.optel.qxinspection.repository.sqlite.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统配置服务（SQLite持久化）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigRepository sysConfigRepository;

    public String get(String key, String defaultValue) {
        String val = sysConfigRepository.findByConfigKey(key)
                .map(SysConfig::getConfigValue)
                .orElse(defaultValue);
        log.debug("SysConfig.get({}) = {}", key, val);
        return val;
    }

    @Transactional(transactionManager = "sqliteTransactionManager")
    public void set(String key, String value) {
        SysConfig config = sysConfigRepository.findByConfigKey(key).orElse(new SysConfig());
        config.setConfigKey(key);
        config.setConfigValue(value);
        SysConfig saved = sysConfigRepository.save(config);
        log.debug("SysConfig.set({}) = {} -> saved={}", key, value, saved != null);
    }
}
