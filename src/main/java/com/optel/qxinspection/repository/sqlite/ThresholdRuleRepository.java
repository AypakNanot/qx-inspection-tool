package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.ThresholdRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ThresholdRuleRepository extends JpaRepository<ThresholdRule, Long> {

    Optional<ThresholdRule> findByLevelTypeAndMatchKey(String levelType, String matchKey);

    List<ThresholdRule> findByLevelType(String levelType);

    List<ThresholdRule> findAllByLevelTypeInOrderByLevelTypeAsc(List<String> levelTypes);
}
