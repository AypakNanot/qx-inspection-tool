package com.optel.qxinspection.repository.mysql;

import com.optel.qxinspection.entity.mysql.DmNet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DmNetRepository extends JpaRepository<DmNet, String> {
}
