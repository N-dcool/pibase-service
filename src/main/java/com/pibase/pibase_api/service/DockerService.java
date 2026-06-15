package com.pibase.pibase_api.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.*;
import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.entity.DatabaseInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerService {

    private final DockerClient dockerClient;
    private final PiBaseProperties piBaseProperties;

    // Image Management

    public void ensureImageExists(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
            log.info("Image {} already present", image);
        } catch (NotFoundException e) {
            log.info("Pulling image: {}...", image);
            try {
                dockerClient.pullImageCmd(image)
                        .start()
                        .awaitCompletion(5, TimeUnit.MINUTES);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Image pull interrupted for " + image, ie);
            }
        }
    }

    public String getImageForEngine(String engine) {
        PiBaseProperties.DockerProperties.ImageProperties images = piBaseProperties.getDocker().getImages();

        return switch (engine.toLowerCase()) {
            case "postgresql" -> images.getPostgresql();
            case "mysql" -> images.getMysql();
            default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
        };
    }

    // Container Lifecycle

    public String createAndStartContainer(DatabaseInstance db, String plainPassword) {

        String image = getImageForEngine(db.getEngine());
        ensureImageExists(image);

        String volumeName = "pibase_" + db.getEngine() + "_" + db.getId();
        String containerName = "pibase_" + db.getId();

        // Create named volume
        dockerClient.createVolumeCmd().withName(volumeName).exec();
        log.info("Volume {} created", volumeName);

        // Build environment variables
        List<String> envVar = buildEnvVars(db, plainPassword);

        // Build host config
        int containerPort = getDefaultPort(db.getEngine());
        HostConfig config = HostConfig.newHostConfig()
                .withPortBindings(new PortBinding(
                        Ports.Binding.bindPort(db.getHostPort()),
                        ExposedPort.tcp(containerPort)))
                .withBinds(new Bind(volumeName, new Volume(getDataDir(db.getEngine()))))
                .withMemory((long) db.getMemoryLimitMb() * 1024 * 1024)
                .withRestartPolicy(RestartPolicy.unlessStoppedRestart());

        // Create container
        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(config)
                .withEnv(envVar)
                .withExposedPorts(ExposedPort.tcp(containerPort))
                .exec();

        // Start container
        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Container {} ({}) started for DB {} on port {}",
                container.getId().substring(0, 12),
                containerName,
                db.getId(),
                db.getHostPort()
        );

        return container.getId();
    }

    public void stopAndRemoveContainer(String containerId, String volumeName) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            log.info("Container {} stopped", containerId.substring(0, 12));
        } catch (NotModifiedException e) {
            log.warn("Container {} already stopped", containerId.substring(0, 12));
        } catch (NotFoundException e) {
            log.warn("Container {} not found - skipping stop", containerId.substring(0, 12));
        }

        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            log.info("Container {} removed", containerId.substring(0, 12));
        } catch (NotFoundException e) {
            log.warn("Container {} not found - skipping remove", containerId.substring(0, 12));
        }

        if (volumeName != null) {
            try {
                dockerClient.removeVolumeCmd(volumeName).exec();
                log.info("Volume {} removed", volumeName);
            } catch (NotFoundException e) {
                log.warn("Volume {} not found - skipping remove", volumeName);
            }
        }
    }

    // Container Inspection

    public String getContainerStatus(String containerId) {
        try {
            InspectContainerResponse info = dockerClient.inspectContainerCmd(containerId).exec();
            InspectContainerResponse.ContainerState state = info.getState();

            return state != null && state.getStatus() != null ? state.getStatus() : "unknown";

        } catch (NotFoundException ex) {
            log.error("Container {} not found", containerId, ex);
            return "not_found";
        }
    }

    public boolean isContainerRunning(String containerId) {
        return "running".equalsIgnoreCase(getContainerStatus(containerId));
    }

    public void waitForReady(String engine, int port, String plainPassword, int timeoutSeconds) {
        String jdbcUrl;
        String user = "dbuser";

        switch (engine.toLowerCase()) {
            case "postgresql" -> jdbcUrl = "jdbc:postgresql://localhost:" + port + "/postgres";
            case "mysql" -> jdbcUrl = "jdbc:mysql://localhost:" + port + "/mysql";
            default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
        }

        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        log.info("Waiting up to {}s for {} on port {} to become ready...",
                timeoutSeconds, engine, port);

        while (Instant.now().isBefore(deadline)) {
            try {
                DriverManager.getConnection(jdbcUrl, user, plainPassword).close();
                log.info("{} on port {} is ready", engine, port);
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during readiness check", ie);
                }
            }
        }

        log.warn("{} on port {} did not become ready within {}s", engine, port, timeoutSeconds);
    }

    public String findContainerIdByName(String containerName) {
        var containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withNameFilter(List.of(containerName))
                .exec();
        if (containers.isEmpty()) {
            return null;
        }

        return containers.getFirst().getId();
    }

    // Docker Engine Info (for health checks)

    public Map<String, Object> getDockerInfo() {
        var info = dockerClient.infoCmd().exec();
        return Map.of(
                "serverVersion", info.getServerVersion() != null ? info.getServerVersion() : "unknown",
                "containers", info.getContainers() != null ? info.getContainers() : 0,
                "containerRunning", info.getContainersRunning() != null ? info.getContainersRunning() : 0,
                "images", info.getImages() != null ? info.getImages() : 0,
                "memoryTotal", info.getMemTotal() != null ? info.getMemTotal() : 0,
                "operatingSystem", info.getOperatingSystem() != null ? info.getOperatingSystem() : "unknown"
        );

    }

    // Private helpers

    private List<String> buildEnvVars(DatabaseInstance db, String plainPassword) {
        List<String> env = new ArrayList<>();

        switch (db.getEngine().toLowerCase()) {
            case "postgresql" -> {
                env.add("POSTGRES_DB=" + db.getDbName());
                env.add("POSTGRES_USER=" + db.getDbUser());
                env.add("POSTGRES_PASSWORD=" + plainPassword);
            }
            case "mysql" -> {
                env.add("MYSQL_DB=" + db.getDbName());
                env.add("MYSQL_USER=" + db.getDbUser());
                env.add("MYSQL_PASSWORD=" + plainPassword);
                env.add("MYSQL_ROOT_PASSWORD=" + plainPassword);
            }
            default -> throw new IllegalArgumentException("Unsupported engine: " + db.getEngine());
        }
        return env;
    }

    private int getDefaultPort(String engine) {
        return switch (engine.toLowerCase()) {
            case "postgresql" -> 5432;
            case "mysql" -> 3306;
            default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
        };
    }

    private String getDataDir(String engine) {
        return switch (engine.toLowerCase()) {
            case "postgresql" -> "/var/lib/postgresql/data";
            case "mysql" -> "/var/lib/mysql";
            default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
        };
    }

}
