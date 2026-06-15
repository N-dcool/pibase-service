package com.pibase.pibase_api.service;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.entity.DbStatus;
import com.pibase.pibase_api.event.DatabaseDeleteEvent;
import com.pibase.pibase_api.event.DatabaseProvisioningEvent;
import com.pibase.pibase_api.exception.QuotaExceededException;
import com.pibase.pibase_api.exception.ResourceNotFoundException;
import com.pibase.pibase_api.repository.DatabaseInstanceRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
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


        // 5. Publish event - Trigger async provisioning @TransactionalEventListener AFTER_COMMIT
        eventPublisher.publishEvent(new DatabaseProvisioningEvent(db.getId(), dbPassword));

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

        // 3. Publish event - Trigger async delete @TransactionalEventListener AFTER_COMMIT
        eventPublisher.publishEvent(new DatabaseDeleteEvent(db.getId()));
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
