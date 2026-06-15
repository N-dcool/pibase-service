package com.pibase.pibase_api.service;

import com.pibase.pibase_api.config.PiBaseProperties;
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

    public synchronized int allocatePort(String engine) {
        PiBaseProperties.PortProperties ports = piBaseProperties.getPorts();
        int min;
        int max;

        switch (engine.toLowerCase()) {
            case "postgresql" -> {
                min = ports.getPostgresqlMin();
                max = ports.getPostgresqlMax();
            }
            case "mysql" -> {
                min = ports.getMysqlMin();
                max = ports.getMysqlMax();
            }
            default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
        }

        Set<Integer> usedPorts = dbRepository.findUsedPortsByEngine(engine);

        for (int port = min; port <= max; port++) {
            if (!usedPorts.contains(port)) {
                log.info("Allocated port {} for {} engine", port, engine);
                return port;
            }
        }

        throw new ProvisioningException("No available ports for " + engine + " (range " + min + "-" + max + " exhausted)");
    }

}
