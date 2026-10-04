package dev.amai.portfolio.system.tag.config;

import dev.amai.portfolio.system.tag.service.TaxonomyBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "portfolio.bootstrap.enabled", havingValue = "true")
public class TaxonomyBootstrapRunner implements ApplicationRunner {
    private final TaxonomyBootstrapService bootstrap;

    public TaxonomyBootstrapRunner(TaxonomyBootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.initialize();
    }
}
