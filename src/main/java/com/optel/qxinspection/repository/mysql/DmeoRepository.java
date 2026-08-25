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

    /**
     * 按 defName 模式查询端口（cid=5），用于巡检时直接从库中获取光口列表
     * patterns 如 ["STM%", "GE%"]，任一匹配即返回（OR 逻辑）
     */
    @Query("SELECT d FROM Dmeo d WHERE d.cid = :cid AND (" +
           "(:p0 IS NULL OR d.defName LIKE :p0) OR " +
           "(:p1 IS NULL OR d.defName LIKE :p1) OR " +
           "(:p2 IS NULL OR d.defName LIKE :p2) OR " +
           "(:p3 IS NULL OR d.defName LIKE :p3) OR " +
           "(:p4 IS NULL OR d.defName LIKE :p4))")
    List<Dmeo> findByCidAndDefNamePatterns(@Param("cid") Integer cid,
                                           @Param("p0") String p0,
                                           @Param("p1") String p1,
                                           @Param("p2") String p2,
                                           @Param("p3") String p3,
                                           @Param("p4") String p4);

    /**
     * 查询某网元下符合 defName 模式的端口
     * neOidPrefix 为网元 oid 前缀（如 "1.3.6.1.4.1.xxx.1"）
     */
    @Query("SELECT d FROM Dmeo d WHERE d.cid = :cid AND d.oid LIKE CONCAT(:neOidPrefix, ':%') AND (" +
           "(:p0 IS NULL OR d.defName LIKE :p0) OR " +
           "(:p1 IS NULL OR d.defName LIKE :p1) OR " +
           "(:p2 IS NULL OR d.defName LIKE :p2) OR " +
           "(:p3 IS NULL OR d.defName LIKE :p3) OR " +
           "(:p4 IS NULL OR d.defName LIKE :p4))")
    List<Dmeo> findByNeOidAndDefNamePatterns(@Param("cid") Integer cid,
                                              @Param("neOidPrefix") String neOidPrefix,
                                              @Param("p0") String p0,
                                              @Param("p1") String p1,
                                              @Param("p2") String p2,
                                              @Param("p3") String p3,
                                              @Param("p4") String p4);
}
