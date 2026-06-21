package com.pibase.pibase_api.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "databases")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DatabaseInstance {


    @Id
    @Column(length = 21)
    private String id;

    @Column(name = "user_id", nullable = false, length = 21)
    private String userId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String engine = "postgresql";

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String engineVersion = "15";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DbStatus status = DbStatus.PROVISIONING;

    @Column(name = "container_id", length = 64)
    private String containerId;

    @Column(name = "container_name", length = 100)
    private String containerName;

    @Column(name = "volume_name", length = 100)
    private String volumeName;

    @Column(name = "db_name", length = 50)
    @Builder.Default
    private String dbName = "dbuser";

    @Column(name = "db_user", nullable = false, length = 50)
    private String dbUser;

    @Column(name = "db_password", nullable = false, length = 500)
    private String dbPassword;

    @Column(name = "host_port")
    private Integer hostPort;

    @Column(name = "pooler_enabled")
    @Builder.Default
    private boolean poolerEnabled = true;

    @Column(name = "direct_uri", length = 500)
    private String directUri;

    @Column(name = "sni_hostname", length = 20, unique = true)
    private String sniHostname;

    @Column(name = "sni_uri", length = 500)
    private String sniUri;

    @Column(name = "pooled_uri", length = 500)
    private String pooledUri;

    @Column(name = "memory_limit_mb")
    @Builder.Default
    private int memoryLimitMb = 128;

    @Column(name = "storage_limit_mb")
    @Builder.Default
    private int storageLimitMb = 100;

    @Column(name = "max_connections")
    @Builder.Default
    private int maxConnections = 10;

    @Column(name = "ttl_hours")
    @Builder.Default
    private int ttlHours = 24;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
