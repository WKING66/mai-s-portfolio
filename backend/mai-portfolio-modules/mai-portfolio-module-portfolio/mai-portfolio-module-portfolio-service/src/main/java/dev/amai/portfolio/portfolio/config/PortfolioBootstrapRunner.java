package dev.amai.portfolio.portfolio.config;

import dev.amai.portfolio.portfolio.service.PortfolioBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "portfolio.bootstrap.enabled", havingValue = "true")
public class PortfolioBootstrapRunner implements ApplicationRunner {
    private final PortfolioBootstrapService bootstrap;

    public PortfolioBootstrapRunner(PortfolioBootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.initialize();
    }
}
