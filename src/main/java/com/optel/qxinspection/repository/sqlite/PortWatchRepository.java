package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.PortWatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PortWatchRepository extends JpaRepository<PortWatch, Long> {

    Optional<PortWatch> findByNeIdAndSlotNoAndPortNo(String neId, Integer slotNo, Integer portNo);
}
