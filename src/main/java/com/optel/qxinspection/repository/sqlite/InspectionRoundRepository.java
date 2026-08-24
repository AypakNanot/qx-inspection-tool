package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.InspectionRound;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface InspectionRoundRepository extends JpaRepository<InspectionRound, Long> {
    Optional<InspectionRound> findFirstByStatusOrderByStartTimeDesc(String status);
    Optional<InspectionRound> findFirstByOrderByStartTimeDesc();
    long countByStatus(String status);
}
