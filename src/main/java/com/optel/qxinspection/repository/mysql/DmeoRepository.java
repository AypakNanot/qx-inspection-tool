package com.optel.qxinspection.repository.mysql;

import com.optel.qxinspection.entity.mysql.Dmeo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DmeoRepository extends JpaRepository<Dmeo, String> {

    List<Dmeo> findByCid(Integer cid);

    long countByCid(Integer cid);

    @Query("SELECT SUBSTRING_INDEX(d.oid, ':', 2), COUNT(d) FROM Dmeo d WHERE d.cid = :cid GROUP BY SUBSTRING_INDEX(d.oid, ':', 2)")
    List<Object[]> countGroupByNePrefix(@Param("cid") Integer cid);

    @Query("SELECT d.type, COUNT(d) FROM Dmeo d WHERE d.cid = :cid GROUP BY d.type")
    List<Object[]> countGroupByType(@Param("cid") Integer cid);
}
