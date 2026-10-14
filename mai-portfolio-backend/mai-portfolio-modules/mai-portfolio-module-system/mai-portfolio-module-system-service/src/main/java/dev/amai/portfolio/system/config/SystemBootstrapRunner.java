package dev.amai.portfolio.system.config;

import dev.amai.portfolio.system.service.SystemBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "portfolio.bootstrap.enabled", havingValue = "true")
public class SystemBootstrapRunner implements ApplicationRunner {
    private final SystemBootstrapService systemBootstrapService;

    public SystemBootstrapRunner(SystemBootstrapService systemBootstrapService) {
        this.systemBootstrapService = systemBootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        systemBootstrapService.initialize();
    }
}
