package com.pibase.pibase_api.service;

import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.entity.DatabaseInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TcpProxyService {

    private final PiBaseProperties piBaseProperties;

    // HAProxy knows the map by this container-internal path
    private static final String HAPROXY_MAP_REF = "/etc/haproxy/maps/db-ports.map";

    /**
     * Register a database in HAProxy's port map.
     * Writes to disk (for restart persistence) + updates in-memory via Runtime API.
     */
    public synchronized void addDatabase(DatabaseInstance db) {
        var config = piBaseProperties.getHaproxy();
        if (!config.isEnabled()) return;

        String slug = db.getSniHostname();
        validateSlug(slug);

        try {
            appendToMapFile(config, slug, db.getHostPort());
            runtimeMapAdd(config, slug, db.getHostPort());

            log.info("[tcp-proxy] Added {} -> 127.0.0.1:{}", slug, db.getHostPort());
        } catch (Exception e) {
            log.error("[tcp-proxy] Failed to add {}", slug, e);
        }
    }

    /**
     * Remove a database from HAProxy's port map.
     */
    public synchronized void removeDatabase(DatabaseInstance db) {
        var config = piBaseProperties.getHaproxy();
        if (!config.isEnabled()) return;

        String slug = db.getSniHostname();
        if (slug == null) return;

        try {
            removeFromMapFile(config, slug);
            runtimeMapDel(config, slug);

            log.info("[tcp-proxy] Removed {}", slug);
        } catch (Exception e) {
            log.error("[tcp-proxy] Failed to remove {}", slug, e);
        }
    }

    /**
     * Full sync - rebuild the map file from all running databases
     * and reload it into HAProxy via Runtime API.
     * Called on application startup (ApplicationReadyEvent).
     */
    public synchronized void syncAll(List<DatabaseInstance> databases) {
        var config = piBaseProperties.getHaproxy();
        if (!config.isEnabled()) return;

        try {
            // Rebuild map file on disk
            StringBuilder mapContent = new StringBuilder();
            for (DatabaseInstance db : databases) {
                String slug = db.getSniHostname();
                if (slug == null) continue;
                validateSlug(slug);
                mapContent.append(slug).append(" ").append(db.getHostPort()).append("\n");
            }

            Files.createDirectories(Path.of(config.getMapFile()).getParent());
            atomicWrite(Path.of(config.getMapFile()), mapContent.toString());

            // Clear in-memory map and reload from disk
            runtimeMapReload(config);

            log.info("[tcp-proxy] Synced {} databases", databases.size());
        } catch (Exception e) {
            log.error("[tcp-proxy] Sync failed", e);
        }
    }

    // Disk operations (persistence for HAProxy restart)
    private void appendToMapFile(PiBaseProperties.HaProxyProperties config, String slug, Integer hostPort) throws IOException {
        Path mapFile = Path.of(config.getMapFile());
        Files.createDirectories(mapFile.getParent());

        // remove old entry if exists, then append new
        List<String> lines = Files.exists(mapFile) ?
                new ArrayList<>(Files.readAllLines(mapFile)) : new ArrayList<>();
        lines.removeIf(line -> line.startsWith(slug + " "));
        lines.add(slug + " " + hostPort);

        atomicWrite(mapFile, String.join("\n", lines) + "\n");
    }

    private void removeFromMapFile(PiBaseProperties.HaProxyProperties config, String slug) throws IOException {
        Path mapFile = Path.of(config.getMapFile());
        if (!Files.exists(mapFile)) return;

        List<String> lines = new ArrayList<>(Files.readAllLines(mapFile));
        lines.removeIf(line -> line.startsWith(slug + " "));

        atomicWrite(mapFile, String.join("\n", lines) + "\n");

    }

    private void atomicWrite(Path target, String content) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.writeString(temp, content);
        // Ensure world-readable (644) so HAProxy (UID 99) can read the map
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // Runtime API operations (instant, zero downtime)

    private void runtimeMapAdd(PiBaseProperties.HaProxyProperties config,
                               String slug, int port) {
        sendSocketCommand(config,
                "add map " + HAPROXY_MAP_REF + " " + slug + " " + port);
    }

    private void runtimeMapDel(PiBaseProperties.HaProxyProperties config,
                               String slug) {
        sendSocketCommand(config,
                "del map " + HAPROXY_MAP_REF + " " + slug);
    }

    private void runtimeMapReload(PiBaseProperties.HaProxyProperties config) {
        // Clear in-memory map, then re-add all entries from disk
        sendSocketCommand(config,
                "clear map " + HAPROXY_MAP_REF);

        try {
            Path mapFile = Path.of(config.getMapFile());
            if (!Files.exists(mapFile)) return;

            for (String line : Files.readAllLines(mapFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 2);
                if (parts.length == 2) {
                    runtimeMapAdd(config, parts[0], Integer.parseInt(parts[1]));
                }
            }
        } catch (IOException e) {
            log.error("[tcp-proxy] Failed to reload map entries", e);
        }
    }

    /**
     * Send a command to HAProxy's Runtime API via Unix domain socket.
     * Uses Java 16+ UnixDomainSocketAddress - no docker exec or socat needed.
     */
    private String sendSocketCommand(PiBaseProperties.HaProxyProperties config, String command) {
        var addr = UnixDomainSocketAddress.of(config.getSocketPath());

        try (SocketChannel channel = SocketChannel.open(addr)) {
            // HAProxy Runtime API expects newline-terminated commands
            channel.write(ByteBuffer.wrap((command + "\n").getBytes()));

            // Read response
            ByteBuffer buf = ByteBuffer.allocate(4096);
            StringBuilder response = new StringBuilder();
            while (channel.read(buf) > 0) {
                buf.flip();
                response.append(new String(buf.array(), 0, buf.limit()));
                buf.clear();
            }

            String result = response.toString().trim();
            if (!result.isEmpty()) {
                log.debug("[tcp-proxy] Runtime API: {} -> {}", command, result);
            }
            return result;

        } catch (IOException e) {
            log.error("[tcp-proxy] Runtime API call failed: {}", command, e);
            return "";
        }
    }

    // Validation

    private void validateSlug(String slug) {
        if (slug == null || !slug.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid HAProxy slug: " + slug);
        }
    }
}
