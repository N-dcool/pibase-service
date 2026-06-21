package com.pibase.pibase_api.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "pibase")
@Data
public class PiBaseProperties {

    private String publicHost = "localhost";
    private String tcpHost = "localhost";
    private DockerProperties docker = new DockerProperties();
    private JwtProperties jwt = new JwtProperties();
    private ProvisioningProperties provisioning = new ProvisioningProperties();
    private PortProperties ports = new PortProperties();
    private HaProxyProperties haproxy = new HaProxyProperties();

    @Data
    public static class DockerProperties {
        private String host = "unix:///var/run/docker.sock";
        private String internalHost = "localhost";
        private int maxConnections = 10;
        private int connectionTimeoutSeconds = 30;
        private int responseTimeoutSeconds = 45;
        private ImageProperties images = new ImageProperties();

        @Data
        public static class ImageProperties {
            private String postgresql = "postgres:15-alpine";
            private String mysql = "mysql:8.0";
        }
    }


    @Data
    public static class JwtProperties {
        @NotBlank
        private String secret;
        private Duration accessTokenExpiry = Duration.ofMinutes(15);
        private Duration refreshTokenExpiry = Duration.ofDays(7);
    }

    @Data
    public static class ProvisioningProperties {
        private int defaultMemoryMb = 128;
        private int defaultStorageMb = 100;
        private int defaultTtlHours = 24;
        private int maxDatabasesPerUser = 1;
    }

    @Data
    public static class PortProperties {
        private int postgresqlMin = 5433;
        private int postgresqlMax = 5532;
        private int mysqlMin = 5533;
        private int mysqlMax = 5582;
    }

    @Data
    public static class HaProxyProperties {
        private String mapFile = "/opt/pibase/haproxy/maps/db-backends.map";
        private String backendsDir = "/opt/pibase/haproxy/backends.d";
        private String containerName = "pibase-haproxy";
        private boolean enabled = false;
    }
}
