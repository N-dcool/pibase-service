package com.pibase.pibase_api.controller;

import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.service.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
@RequestMapping("/api/test/docker")
@RequiredArgsConstructor
@Slf4j
public class DockerTestController {

    private final DockerService dockerService;

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> dockerInfo() {
        return ResponseEntity.ok(dockerService.getDockerInfo());
    }

    @PostMapping("/hello-postgres")
    public ResponseEntity<Map<String, Object>> helloPostgres() {
        DatabaseInstance db = DatabaseInstance.builder()
                .id("test_hello_pg")
                .userId("test_user")
                .engine("postgresql")
                .engineVersion("15")
                .dbName("hello_world")
                .dbUser("testuser")
                .dbPassword("encrypted")
                .hostPort(5499)
                .memoryLimitMb(64)
                .storageLimitMb(50)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        String containerId = dockerService.createAndStartContainer(db, "tempPass");

        return ResponseEntity.ok(Map.of(
                        "message", "Hello World Postgres container started!",
                        "containerId", containerId,
                        "containerName", "pibase_test_user_pg",
                        "hostPort", 5499,
                        "status", dockerService.getContainerStatus(containerId)
                )
        );
    }

    @DeleteMapping("/hello-postgres")
    public ResponseEntity<Map<String, String>> cleanupHelloPostgres() {
        String containerName = "pibase_test_hello_pg";
        String volumeName = "pibase_postgresql_test_hello_pg";

        String containerId = dockerService.findContainerIdByName(containerName);

        if (containerId == null) {
            return ResponseEntity.ok(Map.of(
                    "message", "No test container found"
            ));
        }

        dockerService.stopAndRemoveContainer(containerId, volumeName);

        return ResponseEntity.ok(Map.of(
                "message", "Test container and volume cleaned up"
        ));
    }
}
