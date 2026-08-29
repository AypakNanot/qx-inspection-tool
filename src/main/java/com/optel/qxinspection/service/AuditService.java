package com.optel.qxinspection.service;

import com.optel.qxinspection.entity.sqlite.AuditLog;
import com.optel.qxinspection.repository.sqlite.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void record(String opType, String target, String result, String remark) {
        AuditLog entry = new AuditLog();
        entry.setOpTime(LocalDateTime.now());
        entry.setOpType(opType);
        entry.setTarget(target);
        entry.setResult(result);
        entry.setRemark(remark);
        auditLogRepository.save(entry);
        log.debug("audit: {} {} {} {}", opType, target, result, remark);
    }

    public List<AuditLog> getRecentLogs() {
        return auditLogRepository.findTop200ByOrderByOpTimeDesc();
    }
}
