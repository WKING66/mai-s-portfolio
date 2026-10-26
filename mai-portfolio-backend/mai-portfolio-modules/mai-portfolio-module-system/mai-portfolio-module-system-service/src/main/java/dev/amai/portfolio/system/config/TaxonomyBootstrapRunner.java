package dev.amai.portfolio.system.config;

import dev.amai.portfolio.system.service.TaxonomyBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "portfolio.bootstrap.enabled", havingValue = "true")
public class TaxonomyBootstrapRunner implements ApplicationRunner {
    private final TaxonomyBootstrapService taxonomyBootstrapService;

    public TaxonomyBootstrapRunner(TaxonomyBootstrapService taxonomyBootstrapService) {
        this.taxonomyBootstrapService = taxonomyBootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        taxonomyBootstrapService.initialize();
    }
}
