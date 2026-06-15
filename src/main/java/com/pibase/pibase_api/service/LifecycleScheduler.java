package com.pibase.pibase_api.service;

import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.entity.DbStatus;
import com.pibase.pibase_api.repository.DatabaseInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class LifecycleScheduler {

    private final DatabaseInstanceRepository dbRepository;
    private final DatabaseService databaseService;

    // TTL cleanup every 5 min
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void cleanupExpiredDatabases() {
        // 1. find expired db
        List<DatabaseInstance> expired = dbRepository.findByExpiresAtBeforeAndStatusNot(Instant.now(), DbStatus.DELETED);
        if (expired.isEmpty()) return;

        // 2. force delete all
        log.info("TTL sweep: found {} expired database(s)", expired.size());

        for (DatabaseInstance db : expired) {
            try {
                databaseService.forceDelete(db);
                log.info("TTL: force-deleted database {} (user={}, expired={})", db.getId(), db.getUserId(), db.getExpiresAt());
            } catch (Exception e) {
                log.error("TTL: failed to delete database {}: {}", db.getId(), e.getMessage(), e);
            }
        }

    }

    // Recovery: find stale PROVISIONING record every 5 min
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void recoverStaleProvisions() {
        // 1. find all stale PROVISIONING state
        List<DatabaseInstance> stale = dbRepository.findByStatusAndCreatedAtBefore(
                DbStatus.PROVISIONING,
                Instant.now().minus(5, ChronoUnit.MINUTES));
        if (stale.isEmpty()) return;

        // 2. Update to PROVISION_FAILED status
        log.warn("Recovery: found {} database(s) stuck in PROVISIONING", stale.size());

        for (DatabaseInstance db : stale) {
            log.warn("Recovery: marking database {} as PROVISION_FAILED (stuck since {})", db.getId(), db.getCreatedAt());
            db.setStatus(DbStatus.PROVISION_FAILED);
            dbRepository.save(db);
        }

    }
}
