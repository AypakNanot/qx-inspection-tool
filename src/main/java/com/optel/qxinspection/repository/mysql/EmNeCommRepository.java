package com.optel.qxinspection.repository.mysql;

import com.optel.qxinspection.entity.mysql.EmNeComm;
import com.optel.qxinspection.entity.mysql.EmNeCommId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmNeCommRepository extends JpaRepository<EmNeComm, EmNeCommId> {

    /**
     * 查询设备的活跃IP（state=1）
     */
    @Query("SELECT e FROM EmNeComm e WHERE e.oid = :oid AND e.state = 1")
    Optional<EmNeComm> findActiveByOid(@Param("oid") String oid);

    /**
     * 批量查询设备的活跃IP
     */
    @Query("SELECT e FROM EmNeComm e WHERE e.oid IN :oids AND e.state = 1")
    List<EmNeComm> findActiveByOidIn(@Param("oids") List<String> oids);

    /**
     * 查询设备所有通信记录
     */
    List<EmNeComm> findByOid(String oid);
}
