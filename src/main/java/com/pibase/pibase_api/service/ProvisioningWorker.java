package com.pibase.pibase_api.service;

import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.entity.DatabaseEngine;
import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.entity.DbStatus;
import com.pibase.pibase_api.event.DatabaseDeleteEvent;
import com.pibase.pibase_api.event.DatabaseProvisioningEvent;
import com.pibase.pibase_api.event.DatabaseRestartEvent;
import com.pibase.pibase_api.exception.ResourceNotFoundException;
import com.pibase.pibase_api.repository.DatabaseInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;


@Service
@RequiredArgsConstructor
@Slf4j
public class ProvisioningWorker {

    private final DatabaseInstanceRepository dbRepository;
    private final DockerService dockerService;
    private final PiBaseProperties piBaseProperties;
    private final PlaygroundService playgroundService;

    @TransactionalEventListener
    @Async("provisioningExecutor")
    public void onProvisioningEvent(@NonNull DatabaseProvisioningEvent event) {
        provisionAsync(event.dbId(), event.plainPassword());
    }

    @TransactionalEventListener
    @Async("provisioningExecutor")
    public void onDeleteEvent(@NonNull DatabaseDeleteEvent event) {
        deleteAsync(event.dbId());
    }

    @TransactionalEventListener
    @Async("provisioningExecutor")
    public void onRestartEvent(@NonNull DatabaseRestartEvent event) {
        restartAsync(event.dbId());
    }

    public void provisionAsync(String dbId, String plainPassword) {
        try {
            // 1. get DbInstance
            DatabaseInstance db = dbRepository.findById(dbId)
                    .orElseThrow(() -> new ResourceNotFoundException("Database not found: " + dbId));

            log.info("[Provision-{}] Starting {} container provisioning...", dbId, db.getEngine());

            // 2. create and start docker container
            String containerId = dockerService.createAndStartContainer(db, plainPassword);

            // 3. wait for database readiness - 30 secs
            DatabaseEngine engine = DatabaseEngine.fromId(db.getEngine());
            dockerService.waitForReady(engine, db.getHostPort(), plainPassword, 30);

            // 4. Build connection URIs
            String host = piBaseProperties.getPublicHost();
            String directUri = engine.buildDirectUri(db.getDbUser(), plainPassword, host, db.getHostPort(), db.getDbName());

            // 5. Update metadata
            db.setContainerId(containerId);
            db.setStatus(DbStatus.RUNNING);
            db.setDirectUri(directUri);
            dbRepository.save(db);

            log.info("[Provision-{}] Database provisioned successfully -- {}:{}", dbId, host, db.getHostPort());

        } catch (Exception e) {
            log.error("[Provision-{}] provisioning failed: {}", dbId, e.getMessage(), e);
            // update db metadata
            dbRepository.findById(dbId).ifPresent(db -> {
                db.setStatus(DbStatus.PROVISION_FAILED);
                dbRepository.save(db);
            });
        }
    }

    public void deleteAsync(String dbId) {
        try {
            // 1. get DbInstance
            DatabaseInstance db = dbRepository.findById(dbId)
                    .orElseThrow(() -> new ResourceNotFoundException("Database not found: " + dbId));

            // evict pool
            playgroundService.evictPool(dbId);

            // 2. stop and remove container
            if (db.getContainerId() != null) {
                dockerService.stopAndRemoveContainer(db.getContainerId(), db.getVolumeName());
            }

            // 3. update metadata
            db.setStatus(DbStatus.DELETED);
            db.setDeletedAt(Instant.now());
            dbRepository.save(db);

            log.info("Database {} deleted successfully", dbId);

        } catch (Exception e) {
            log.error("Failed to delete database {}: {}", dbId, e.getMessage(), e);
        }
    }

    public void restartAsync(String dbId) {
        try {
            // 1. find Db instance
            DatabaseInstance db = dbRepository.findById(dbId)
                    .orElseThrow(() -> new ResourceNotFoundException("Database not found: " + dbId));

            // 2. Stop and Restart container
            log.info("[restart-{}] Stopping container...", dbId);
            dockerService.stopContainer(db.getContainerId());

            log.info("[restart-{}] Starting container...", dbId);
            dockerService.startContainer(db.getContainerId());

            // 3. update db status
            db.setStatus(DbStatus.RUNNING);
            dbRepository.save(db);

            log.info("[restart-{}] Database restarted successfully", dbId);

        } catch (Exception e) {
            log.error("[restart-{}] Restart failed: {}", dbId, e.getMessage(), e);
            dbRepository.findById(dbId).ifPresent(db -> {
                db.setStatus(DbStatus.START_FAILED);
                dbRepository.save(db);
            });
        }
    }

}
