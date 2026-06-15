package com.pibase.pibase_api.repository;

import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.entity.DbStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface DatabaseInstanceRepository extends JpaRepository<DatabaseInstance, String> {

    Optional<DatabaseInstance> findByUserIdAndStatusIn(String userId, List<DbStatus> statuses);

    long countByUserIdAndStatusIn(String userId, List<DbStatus> statuses);

    List<DatabaseInstance> findByStatus(DbStatus status);

    List<DatabaseInstance> findByExpiresAtBeforeAndStatusNot(Instant before, DbStatus status);

    List<DatabaseInstance> findByStatusAndCreatedAtBefore(DbStatus status, Instant before);

    @Query("SELECT d.hostPort FROM DatabaseInstance d WHERE d.engine = :engine AND d.status <> 'DELETED'")
    Set<Integer> findUsedPortsByEngine(@Param("engine") String engine);

}
