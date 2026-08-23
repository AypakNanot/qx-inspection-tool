package com.optel.qxinspection.repository.mysql;

import com.optel.qxinspection.entity.mysql.DefDmNe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DefDmNeRepository extends JpaRepository<DefDmNe, Integer> {

    Optional<DefDmNe> findByNeType(Integer neType);
}
