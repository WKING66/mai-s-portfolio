package dev.amai.portfolio.config;

import dev.amai.portfolio.service.BootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "portfolio.bootstrap.enabled", havingValue = "true")
public class BootstrapRunner implements ApplicationRunner {
    private final BootstrapService bootstrap;

    public BootstrapRunner(BootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.initialize();
    }
}
