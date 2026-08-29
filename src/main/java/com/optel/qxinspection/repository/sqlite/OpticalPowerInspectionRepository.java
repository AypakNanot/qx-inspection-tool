package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.OpticalPowerInspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OpticalPowerInspectionRepository extends JpaRepository<OpticalPowerInspection, Long> {

    List<OpticalPowerInspection> findByRoundId(Long roundId);

    List<OpticalPowerInspection> findByRoundIdIn(List<Long> roundIds);

    List<OpticalPowerInspection> findByRoundIdAndNeId(Long roundId, String neId);

    List<OpticalPowerInspection> findByRoundIdAndNetworkName(Long roundId, String networkName);

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

    // 趋势查询：指定网元+槽位+端口的历史记录
    @Query("SELECT o FROM OpticalPowerInspection o WHERE o.neId = :neId AND o.slotNo = :slotNo AND o.portNo = :portNo AND o.supported = true ORDER BY o.roundId DESC")
    List<OpticalPowerInspection> findTrendByPort(@Param("neId") String neId,
                                                  @Param("slotNo") int slotNo,
                                                  @Param("portNo") int portNo);

    // 指定网元的历史记录（所有端口）
    @Query("SELECT o FROM OpticalPowerInspection o WHERE o.neId = :neId AND o.supported = true ORDER BY o.roundId DESC, o.slotNo, o.portNo")
    List<OpticalPowerInspection> findTrendByNe(@Param("neId") String neId);

    // 最新轮次中越限的记录
    @Query("SELECT o FROM OpticalPowerInspection o WHERE o.roundId = :roundId AND (o.txPowerStatus > 0 OR o.rxPowerStatus > 0)")
    List<OpticalPowerInspection> findOverThresholdByRoundId(@Param("roundId") Long roundId);

    // 指定轮次按网元分组的越限计数
    @Query("SELECT o.neId, o.neName, COUNT(o) FROM OpticalPowerInspection o WHERE o.roundId = :roundId AND (o.txPowerStatus > 0 OR o.rxPowerStatus > 0) GROUP BY o.neId, o.neName")
    List<Object[]> countOverThresholdGroupByNe(@Param("roundId") Long roundId);

    // 最新轮次中去重的端口名列表
    @Query("SELECT DISTINCT o.portName FROM OpticalPowerInspection o WHERE o.roundId = (SELECT MAX(o2.roundId) FROM OpticalPowerInspection o2) AND o.portName IS NOT NULL AND o.portName <> '' ORDER BY o.portName")
    List<String> findDistinctPortNamesInLatestRound();
}
