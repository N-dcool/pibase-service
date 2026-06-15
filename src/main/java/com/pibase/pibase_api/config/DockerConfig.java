package com.pibase.pibase_api.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DockerConfig {

    private final PiBaseProperties piBaseProperties;

    @Bean
    public DockerClient dockerClient() {
        PiBaseProperties.DockerProperties docker = piBaseProperties.getDocker();

        DockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost(docker.getHost())
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(docker.getMaxConnections())
                .connectionTimeout(Duration.ofSeconds(docker.getConnectionTimeoutSeconds()))
                .responseTimeout(Duration.ofSeconds(docker.getResponseTimeoutSeconds()))
                .build();

        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        log.info("Docker client initialized - host: {}", docker.getHost());

        return client;
    }
}
