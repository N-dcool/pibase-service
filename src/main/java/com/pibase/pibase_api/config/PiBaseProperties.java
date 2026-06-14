package com.pibase.pibase_api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "pibase")
@Data
public class PiBaseProperties {

    private String publicHost = "localhost";
    private ProvisioningProperties provisioning = new ProvisioningProperties();
    private PortProperties ports = new PortProperties();

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
}
