package com.pibase.pibase_api.dto.response;

import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.entity.DbStatus;

import java.time.Instant;

public record DatabaseResponse(
        String id,
        String engine,
        String engineVersion,
        DbStatus status,
        String dbName,
        String dbUser,
        Integer hostPort,
        String directUri,
        String pooledUri,
        int memoryLimitMb,
        int storageLimitMb,
        int ttlHours,
        Instant expiresAt,
        Instant createdAt
) {
    public static DatabaseResponse from(DatabaseInstance db) {
        return new DatabaseResponse(
                db.getId(),
                db.getEngine(),
                db.getEngineVersion(),
                db.getStatus(),
                db.getDbName(),
                db.getDbUser(),
                db.getHostPort(),
                db.getDirectUri(),
                db.getPooledUri(),
                db.getMemoryLimitMb(),
                db.getStorageLimitMb(),
                db.getTtlHours(),
                db.getExpiresAt(),
                db.getCreatedAt()
        );
    }
}
