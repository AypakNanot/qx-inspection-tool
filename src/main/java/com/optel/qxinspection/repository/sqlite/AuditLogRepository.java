package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop200ByOrderByOpTimeDesc();
}
