package com.pibase.pibase_api.service;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.entity.DbStatus;
import com.pibase.pibase_api.exception.QuotaExceededException;
import com.pibase.pibase_api.exception.ResourceNotFoundException;
import com.pibase.pibase_api.repository.DatabaseInstanceRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseService {

    private final DatabaseInstanceRepository dbRepository;
    private final DockerService dockerService;
    private final PortManagerService portManager;
    private final PiBaseProperties piBaseProperties;

    private static final List<DbStatus> ACTIVE_STATUSES = List.of(
            DbStatus.PROVISIONING,
            DbStatus.RUNNING
    );
    private static final char[] ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int ID_LENGTH = 5;

    @Transactional
    public DatabaseInstance createDatabase(String userId, String engine) {
        // 1. check quota
        long activeCount = dbRepository.countByUserIdAndStatusIn(userId, ACTIVE_STATUSES);
        PiBaseProperties.ProvisioningProperties prov = piBaseProperties.getProvisioning();
        if (activeCount >= prov.getMaxDatabasesPerUser()) {
            throw new QuotaExceededException("Maximum " + prov.getMaxDatabasesPerUser() + " active database(s) per user");
        }

        // 2. Allocate port
        int hostPort = portManager.allocatePort(engine);

        // 3. Generate credentials
        String dbPassword = generatePassword();
        String dbId = NanoIdUtils.randomNanoId(new SecureRandom(), ID_ALPHABET, ID_LENGTH);
        String dbName = "db_" + dbId.toLowerCase();
        String volumeName = "pibase_" + engine + "_" + dbId;
        String containerName = "pibase_" + dbId;

        // 4. create metadata record
        DatabaseInstance db = DatabaseInstance.builder()
                .id(dbId)
                .userId(userId)
                .engine(engine)
                .engineVersion(engine.equals("postgresql") ? "15" : "8.0")
                .status(DbStatus.PROVISIONING)
                .dbName(dbName)
                .dbUser("dbuser")
                .dbPassword(Base64.getEncoder().encodeToString(dbPassword.getBytes()))
                .hostPort(hostPort)
                .containerName(containerName)
                .volumeName(volumeName)
                .memoryLimitMb(prov.getDefaultMemoryMb())
                .storageLimitMb(prov.getDefaultStorageMb())
                .ttlHours(prov.getDefaultTtlHours())
                .expiresAt(Instant.now().plus(prov.getDefaultTtlHours(), ChronoUnit.HOURS))
                .build();
        db = dbRepository.save(db);
        log.info("Database record created: {} (engine={}, port={}, user={})", db.getId(), engine, hostPort, userId);


        // 5. Trigger async provisioning
        provisionAsync(db.getId(), dbPassword);

        return db;
    }

    @Transactional
    public void deleteDatabase(String userId) {
        // 1. get DB instance
        DatabaseInstance db = dbRepository.findByUserIdAndStatusIn(userId, ACTIVE_STATUSES)
                .orElseThrow(() -> new ResourceNotFoundException("No active database found"));

        log.info("Delete requested for database {} by user {}", db.getId(), userId);

        // 2. update status = deleting
        db.setStatus(DbStatus.DELETING);
        dbRepository.save(db);

        // 3. Trigger async delete
        deleteAsync(db.getId());
    }

    @Async("provisioningExecutor")
    public void provisionAsync(String dbId, String plainPassword) {
        try {
            // 1. get DbInstance
            DatabaseInstance db = dbRepository.findById(dbId)
                    .orElseThrow(() -> new ResourceNotFoundException("Database not found: " + dbId));

            log.info("[Provision-{}] Starting {} container provisioning...", dbId, db.getEngine());

            // 2. create and start docker container
            String containerId = dockerService.createAndStartContainer(db, plainPassword);

            // 3. wait for database readiness - 30 secs
            dockerService.waitForReady(db.getEngine(), db.getHostPort(), plainPassword, 30);

            // 4. Build connection URIs
            String host = piBaseProperties.getPublicHost();
            String directUri = buildDirectUri(db, plainPassword, host);

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

    @Async("provisioningExecutor")
    public void deleteAsync(String dbId) {
        try {
            // 1. get DbInstance
            DatabaseInstance db = dbRepository.findById(dbId)
                    .orElseThrow(() -> new ResourceNotFoundException("Database not found: " + dbId));

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

    private String buildDirectUri(DatabaseInstance db, String password, String host) {
        return switch (db.getEngine().toLowerCase()) {
            case "postgresql" ->
                    String.format("postgresql://%s:%s@%s:%d/%s", db.getDbUser(), password, host, db.getHostPort(), db.getDbName());
            case "mysql" ->
                    String.format("mysql://%s:%s@%s:%d/%s", db.getDbUser(), password, host, db.getHostPort(), db.getDbName());
            default -> null;
        };
    }

    public Optional<DatabaseInstance> findActiveByUserId(String userId) {
        return dbRepository.findByUserIdAndStatusIn(userId, ACTIVE_STATUSES);
    }

    public Optional<DatabaseInstance> findByIdAndUserId(String dbId, String userId) {
        return dbRepository.findById(dbId).filter(db -> db.getUserId().equals(userId));
    }

    private String generatePassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
