package com.bookkeep.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StartupInfoRunner implements ApplicationRunner {

    private final Environment environment;
    private final BuildProperties buildProperties;

    @Autowired
    public StartupInfoRunner(Environment environment,
                             @Autowired(required = false) BuildProperties buildProperties) {
        this.environment = environment;
        this.buildProperties = buildProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String name = buildProperties != null ? buildProperties.getName() : "bookkeep-server";
        String version = buildProperties != null ? buildProperties.getVersion() : "unknown";
        String port = environment.getProperty("server.port", "8080");
        String[] profiles = environment.getActiveProfiles();
        String profile = profiles.length > 0 ? String.join(",", profiles) : "default";
        log.info("==========================================================");
        log.info("  {} 启动成功", name);
        log.info("  版本: {}   端口: {}   环境: {}", version, port, profile);
        log.info("==========================================================");
    }
}
