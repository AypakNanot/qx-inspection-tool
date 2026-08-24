package com.optel.qxinspection.repository.mysql;

import com.optel.qxinspection.entity.mysql.DmRelation;
import com.optel.qxinspection.entity.mysql.DmRelationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DmRelationRepository extends JpaRepository<DmRelation, DmRelationId> {

    /**
     * 查询网元的网络归属（type=1 为归属关系）
     */
    @Query("SELECT r FROM DmRelation r WHERE r.oid = :oid AND r.type = 1")
    List<DmRelation> findNetworkByOid(@Param("oid") String oid);

    /**
     * 查询指定网络下的所有网元
     */
    @Query("SELECT r FROM DmRelation r WHERE r.reo = :netOid AND r.type = 1")
    List<DmRelation> findByReo(@Param("netOid") String netOid);
}
