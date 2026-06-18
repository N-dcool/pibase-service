package com.pibase.pibase_api.service;

import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.entity.DatabaseEngine;
import com.pibase.pibase_api.exception.ProvisioningException;
import com.pibase.pibase_api.repository.DatabaseInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortManagerService {

    private final DatabaseInstanceRepository dbRepository;
    private final PiBaseProperties piBaseProperties;

    public synchronized int allocatePort(DatabaseEngine engine) {
        PiBaseProperties.PortProperties ports = piBaseProperties.getPorts();
        int min = switch (engine) {
            case POSTGRESQL -> ports.getPostgresqlMin();
            case MYSQL -> ports.getMysqlMin();
        };
        int max = switch (engine) {
            case POSTGRESQL -> ports.getPostgresqlMax();
            case MYSQL -> ports.getMysqlMax();
        };

        Set<Integer> usedPorts = dbRepository.findUsedPortsByEngine(engine.getId());

        for (int port = min; port <= max; port++) {
            if (!usedPorts.contains(port)) {
                log.info("Allocated port {} for {} engine", port, engine.getId());
                return port;
            }
        }

        throw new ProvisioningException("No available ports for " + engine.getId() + " (range " + min + "-" + max + " exhausted)");
    }

}
