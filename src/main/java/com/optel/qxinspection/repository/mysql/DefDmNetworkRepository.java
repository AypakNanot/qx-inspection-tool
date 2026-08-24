package com.optel.qxinspection.repository.mysql;

import com.optel.qxinspection.entity.mysql.DefDmNetwork;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DefDmNetworkRepository extends JpaRepository<DefDmNetwork, Integer> {
}
