package com.optel.qxinspection.repository.mysql;

import com.optel.qxinspection.entity.mysql.DmNe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DmNeRepository extends JpaRepository<DmNe, String> {

    List<DmNe> findByType(Integer type);

    List<DmNe> findByCommuState(Integer commuState);

    @Query("SELECT n FROM DmNe n WHERE n.commuState = 1")
    List<DmNe> findAllOnline();

    @Query("SELECT n.type, COUNT(n) FROM DmNe n GROUP BY n.type")
    List<Object[]> countByType();
}
