package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.service.impl.AssetQueryServiceImpl;
import dev.amai.portfolio.portfolio.config.PortfolioBootstrapRunner;
import dev.amai.portfolio.portfolio.controller.ProfileController;
import dev.amai.portfolio.portfolio.service.PortfolioBootstrapService;
import dev.amai.portfolio.portfolio.service.ProfileService;
import dev.amai.portfolio.portfolio.service.impl.PortfolioBootstrapServiceImpl;
import dev.amai.portfolio.portfolio.service.impl.ProfileServiceImpl;
import dev.amai.portfolio.security.password.Argon2PasswordHasher;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.auth.AccountRoleProvider;
import dev.amai.portfolio.system.auth.AdminAuthorization;
import dev.amai.portfolio.system.config.SystemBootstrapRunner;
import dev.amai.portfolio.system.controller.AdminSessionController;
import dev.amai.portfolio.system.service.AdminSessionService;
import dev.amai.portfolio.system.service.LoginChallengeService;
import dev.amai.portfolio.system.service.OwnerRoleService;
import dev.amai.portfolio.system.service.SystemBootstrapService;
import dev.amai.portfolio.system.service.impl.AdminSessionServiceImpl;
import dev.amai.portfolio.system.service.impl.LoginChallengeServiceImpl;
import dev.amai.portfolio.system.service.impl.OwnerRoleServiceImpl;
import dev.amai.portfolio.system.service.impl.SystemBootstrapServiceImpl;
import dev.amai.portfolio.taxonomy.api.TaxonomyQueryService;
import dev.amai.portfolio.taxonomy.config.TaxonomyBootstrapRunner;
import dev.amai.portfolio.taxonomy.service.TaxonomyBootstrapService;
import dev.amai.portfolio.taxonomy.service.impl.TaxonomyBootstrapServiceImpl;
import dev.amai.portfolio.taxonomy.service.impl.TaxonomyQueryServiceImpl;
import org.junit.jupiter.api.Test;

class ServiceArchitectureTest {
    @Test
    void servicesAreInterfacesWithDedicatedImplementations() {
        assertService(ProfileService.class, ProfileServiceImpl.class);
        assertService(PortfolioBootstrapService.class, PortfolioBootstrapServiceImpl.class);
        assertService(AdminSessionService.class, AdminSessionServiceImpl.class);
        assertService(OwnerRoleService.class, OwnerRoleServiceImpl.class);
        assertThat(PasswordHasher.class.isAssignableFrom(Argon2PasswordHasher.class)).isTrue();
        assertService(SystemBootstrapService.class, SystemBootstrapServiceImpl.class);
        assertService(LoginChallengeService.class, LoginChallengeServiceImpl.class);
        assertService(AssetQueryService.class, AssetQueryServiceImpl.class);
        assertService(TaxonomyQueryService.class, TaxonomyQueryServiceImpl.class);
        assertService(TaxonomyBootstrapService.class, TaxonomyBootstrapServiceImpl.class);
    }

    @Test
    void httpBoundariesDependOnServiceInterfaces() {
        assertThat(ProfileController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(ProfileService.class);
        assertThat(AdminSessionController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(AdminSessionService.class);
        assertThat(AdminAuthorization.class.getDeclaredConstructors()[0].getParameterTypes())
            .isEmpty();
        assertThat(AccountRoleProvider.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(OwnerRoleService.class);
        assertThat(SystemBootstrapRunner.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(SystemBootstrapService.class);
        assertThat(PortfolioBootstrapRunner.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(PortfolioBootstrapService.class);
        assertThat(TaxonomyBootstrapRunner.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(TaxonomyBootstrapService.class);
    }

    private void assertService(Class<?> contract, Class<?> implementation) {
        assertThat(contract.isInterface()).isTrue();
        assertThat(contract.isAssignableFrom(implementation)).isTrue();
        assertThat(implementation.getPackageName()).endsWith(".service.impl");
    }
}
