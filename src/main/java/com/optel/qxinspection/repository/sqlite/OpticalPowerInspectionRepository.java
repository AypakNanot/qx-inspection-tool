package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OpticalPowerInspectionRepository extends JpaRepository<OpticalPowerInspection, Long> {

    List<OpticalPowerInspection> findByRoundId(Long roundId);

    List<OpticalPowerInspection> findByRoundIdAndNeId(Long roundId, String neId);

    @Query("SELECT o FROM OpticalPowerInspection o WHERE o.roundId = :roundId AND o.supported = true")
    List<OpticalPowerInspection> findSupportedByRoundId(@Param("roundId") Long roundId);

    @Query("SELECT DISTINCT o.neId FROM OpticalPowerInspection o WHERE o.roundId = :roundId")
    List<String> findDistinctNeIdsByRoundId(@Param("roundId") Long roundId);

    @Query("SELECT COUNT(DISTINCT o.neId) FROM OpticalPowerInspection o WHERE o.roundId = :roundId")
    long countDistinctNeIdsByRoundId(@Param("roundId") Long roundId);

    @Query("SELECT COUNT(o) FROM OpticalPowerInspection o WHERE o.roundId = :roundId AND o.txPowerStatus > 0 OR o.roundId = :roundId AND o.rxPowerStatus > 0")
    long countOverThresholdByRoundId(@Param("roundId") Long roundId);

    @Modifying
    @Query("DELETE FROM OpticalPowerInspection o WHERE o.roundId < :minRoundId")
    void deleteByRoundIdLessThan(@Param("minRoundId") Long minRoundId);
}
