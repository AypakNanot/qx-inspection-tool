package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.LinkInspectionResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LinkInspectionResultRepository extends JpaRepository<LinkInspectionResult, Long> {

    List<LinkInspectionResult> findByRoundId(Long roundId);
}