package com.pibase.pibase_api.service;

import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.entity.DatabaseInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TcpProxyService {

    private final PiBaseProperties piBaseProperties;

    public synchronized void addDatabase(DatabaseInstance db) {
        PiBaseProperties.HaProxyProperties config = piBaseProperties.getHaproxy();

        if (!config.isEnabled()) {
            return;
        }

        String slug = db.getSniHostname();
        validateSlug(slug);

        try {
            createDirectories(config);

            writeBackendConfig(config, db);
            updateMapEntry(config, slug, true);

            reloadHaProxy(config);

            log.info(
                    "[tcp-proxy] Added backend {} -> 127.0.0.1:{}",
                    slug,
                    db.getHostPort());

        } catch (Exception e) {
            log.error("[tcp-proxy] Failed to add backend {}", slug, e);
        }
    }

    public synchronized void removeDatabase(DatabaseInstance db) {
        PiBaseProperties.HaProxyProperties config = piBaseProperties.getHaproxy();

        if (!config.isEnabled()) {
            return;
        }

        String slug = db.getSniHostname();

        if (slug == null) {
            return;
        }

        try {
            Path backendFile = Path.of(config.getBackendsDir(), slug + ".cfg");

            Files.deleteIfExists(backendFile);

            updateMapEntry(config, slug, false);

            reloadHaProxy(config);

            log.info("[tcp-proxy] Removed backend {}", slug);

        } catch (Exception e) {
            log.error("[tcp-proxy] Failed to remove backend {}", slug, e);
        }
    }

    public synchronized void syncAll(List<DatabaseInstance> databases) {
        PiBaseProperties.HaProxyProperties config = piBaseProperties.getHaproxy();

        if (!config.isEnabled()) {
            return;
        }

        try {
            createDirectories(config);

            clearBackendDirectory(config);

            StringBuilder mapContent = new StringBuilder();

            for (DatabaseInstance db : databases) {

                String slug = db.getSniHostname();

                if (slug == null) {
                    continue;
                }

                validateSlug(slug);

                writeBackendConfig(config, db);

                mapContent
                        .append(slug)
                        .append(" bk_")
                        .append(slug)
                        .append("\n");
            }

            atomicWrite(
                    Path.of(config.getMapFile()),
                    mapContent.toString());

            reloadHaProxy(config);

            log.info(
                    "[tcp-proxy] Synced {} active backends",
                    databases.size());

        } catch (Exception e) {
            log.error("[tcp-proxy] Sync failed", e);
        }
    }

    private void writeBackendConfig(
            PiBaseProperties.HaProxyProperties config,
            DatabaseInstance db) throws IOException {

        String slug = db.getSniHostname();

        String content = """
                backend bk_%s
                    mode tcp
                    server db1 127.0.0.1:%d check
                """.formatted(
                slug,
                db.getHostPort());

        Path file = Path.of(config.getBackendsDir(), slug + ".cfg");

        atomicWrite(file, content);
    }

    private void updateMapEntry(
            PiBaseProperties.HaProxyProperties config,
            String slug,
            boolean add) throws IOException {

        Path mapFile = Path.of(config.getMapFile());

        List<String> lines = Files.exists(mapFile)
                ? Files.readAllLines(mapFile)
                : new ArrayList<>();

        lines = lines.stream()
                .filter(line -> !line.startsWith(slug + " "))
                .collect(Collectors.toList());

        if (add) {
            lines.add(slug + " bk_" + slug);
        }

        atomicWrite(
                mapFile,
                String.join("\n", lines) + "\n");
    }

    private void createDirectories(
            PiBaseProperties.HaProxyProperties config) throws IOException {

        Files.createDirectories(
                Path.of(config.getBackendsDir()));

        Files.createDirectories(
                Path.of(config.getMapFile()).getParent());
    }

    private void clearBackendDirectory(
            PiBaseProperties.HaProxyProperties config) throws IOException {

        Path backendDir = Path.of(config.getBackendsDir());

        if (!Files.exists(backendDir)) {
            return;
        }

        try (var files = Files.list(backendDir)) {

            files.filter(p -> p.toString().endsWith(".cfg"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private void atomicWrite(Path target, String content)
            throws IOException {

        Path temp = Files.createTempFile(
                target.getParent(),
                target.getFileName().toString(),
                ".tmp");

        Files.writeString(temp, content);

        Files.move(
                temp,
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private void validateSlug(String slug) {

        if (slug == null ||
                !slug.matches("[a-zA-Z0-9_-]+")) {

            throw new IllegalArgumentException(
                    "Invalid HAProxy slug: " + slug);
        }
    }

    private void reloadHaProxy(
            PiBaseProperties.HaProxyProperties config) {

        try {

            Process validate = new ProcessBuilder(
                    "docker",
                    "exec",
                    config.getContainerName(),
                    "haproxy",
                    "-c",
                    "-f",
                    "/usr/local/etc/haproxy/haproxy.cfg",
                    "-f",
                    "/etc/haproxy/backends.d/"
            ).start();

            if (validate.waitFor() != 0) {
                log.error(
                        "[tcp-proxy] HAProxy config validation failed");
                return;
            }

            Process reload = new ProcessBuilder(
                    "docker",
                    "kill",
                    "-s",
                    "HUP",
                    config.getContainerName()).start();

            int exit = reload.waitFor();

            if (exit != 0) {
                log.warn(
                        "[tcp-proxy] HAProxy reload exited with {}",
                        exit);
            }

        } catch (Exception e) {
            log.error(
                    "[tcp-proxy] Reload failed",
                    e);
        }
    }
}
