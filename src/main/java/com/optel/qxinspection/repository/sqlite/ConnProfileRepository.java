package com.optel.qxinspection.repository.sqlite;

import com.optel.qxinspection.entity.sqlite.ConnProfile;
import com.optel.qxinspection.entity.sqlite.ConnProfileId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConnProfileRepository extends JpaRepository<ConnProfile, ConnProfileId> {

    Optional<ConnProfile> findByScopeAndNeOid(String scope, String neOid);

    List<ConnProfile> findByScope(String scope);

    Optional<ConnProfile> findByScopeAndNeOidAndAutoConnect(String scope, String neOid, Integer autoConnect);
}
