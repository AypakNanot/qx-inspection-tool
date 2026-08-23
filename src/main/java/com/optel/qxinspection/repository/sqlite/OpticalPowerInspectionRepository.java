package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OpticalPowerInspectionRepository extends JpaRepository<OpticalPowerInspection, Long> {

    List<OpticalPowerInspection> findByNeId(String neId);

    List<OpticalPowerInspection> findByBatchNo(String batchNo);

    List<OpticalPowerInspection> findByInspectionTimeBetween(LocalDateTime startTime, LocalDateTime endTime);

    List<OpticalPowerInspection> findByNeIdAndInspectionTimeBetween(
            String neId, LocalDateTime startTime, LocalDateTime endTime);

    @Query("SELECT o FROM OpticalPowerInspection o WHERE o.txPowerStatus > 0 OR o.rxPowerStatus > 0")
    List<OpticalPowerInspection> findOverThresholdRecords();

    @Query("SELECT o FROM OpticalPowerInspection o WHERE o.neId = :neId ORDER BY o.inspectionTime DESC LIMIT 1")
    OpticalPowerInspection findLatestByNeId(@Param("neId") String neId);

    @Query("SELECT COUNT(o) FROM OpticalPowerInspection o WHERE o.inspectionTime BETWEEN :startTime AND :endTime AND (o.txPowerStatus > 0 OR o.rxPowerStatus > 0)")
    Long countOverThresholdByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}
