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
import dev.amai.portfolio.system.auth.AuthenticationInterceptor;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.config.SystemBootstrapRunner;
import dev.amai.portfolio.system.controller.AuthController;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.system.service.OwnerRoleService;
import dev.amai.portfolio.system.service.SystemBootstrapService;
import dev.amai.portfolio.system.service.impl.AuthServiceImpl;
import dev.amai.portfolio.system.service.impl.PasswordCryptoServiceImpl;
import dev.amai.portfolio.system.service.impl.OwnerRoleServiceImpl;
import dev.amai.portfolio.system.service.impl.SystemBootstrapServiceImpl;
import dev.amai.portfolio.system.tag.api.TaxonomyQueryService;
import dev.amai.portfolio.system.tag.config.TaxonomyBootstrapRunner;
import dev.amai.portfolio.system.tag.service.TaxonomyBootstrapService;
import dev.amai.portfolio.system.tag.service.impl.TaxonomyBootstrapServiceImpl;
import dev.amai.portfolio.system.tag.service.impl.TaxonomyQueryServiceImpl;
import org.junit.jupiter.api.Test;

class ServiceArchitectureTest {
    @Test
    void tagContractsAndImplementationBelongToSystemModule() {
        assertThat(TaxonomyQueryService.class.getPackageName()).isEqualTo("dev.amai.portfolio.system.tag.api");
        assertThat(TaxonomyQueryServiceImpl.class.getPackageName())
            .isEqualTo("dev.amai.portfolio.system.tag.service.impl");
        assertThat(TaxonomyBootstrapRunner.class.getPackageName())
            .isEqualTo("dev.amai.portfolio.system.tag.config");
    }

    @Test
    void servicesAreInterfacesWithDedicatedImplementations() {
        assertService(ProfileService.class, ProfileServiceImpl.class);
        assertService(PortfolioBootstrapService.class, PortfolioBootstrapServiceImpl.class);
        assertService(AuthService.class, AuthServiceImpl.class);
        assertService(OwnerRoleService.class, OwnerRoleServiceImpl.class);
        assertThat(PasswordHasher.class.isAssignableFrom(Argon2PasswordHasher.class)).isTrue();
        assertService(SystemBootstrapService.class, SystemBootstrapServiceImpl.class);
        assertService(PasswordCryptoService.class, PasswordCryptoServiceImpl.class);
        assertService(AssetQueryService.class, AssetQueryServiceImpl.class);
        assertService(TaxonomyQueryService.class, TaxonomyQueryServiceImpl.class);
        assertService(TaxonomyBootstrapService.class, TaxonomyBootstrapServiceImpl.class);
    }

    @Test
    void httpBoundariesDependOnServiceInterfaces() {
        assertThat(ProfileController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(ProfileService.class);
        assertThat(AuthController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(AuthService.class);
        assertThat(AdminAuthorization.class.getDeclaredConstructors()[0].getParameterTypes())
            .isEmpty();
        assertThat(AuthenticationInterceptor.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(SecurityProperties.class, AuthService.class);
        assertThat(AccountRoleProvider.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(OwnerRoleService.class);
        assertThat(SystemBootstrapRunner.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(SystemBootstrapService.class);
        assertThat(PortfolioBootstrapRunner.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(PortfolioBootstrapService.class);
        assertThat(TaxonomyBootstrapRunner.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(TaxonomyBootstrapService.class);
    }

    @Test
    void logoutRequiresLoginRatherThanOwnerRole() throws NoSuchMethodException {
        var logout = AuthController.class.getDeclaredMethod("logout");
        assertThat(logout.isAnnotationPresent(cn.dev33.satoken.annotation.SaCheckLogin.class)).isTrue();
        assertThat(logout.isAnnotationPresent(cn.dev33.satoken.annotation.SaCheckRole.class)).isFalse();
        assertThat(AuthController.class.isAnnotationPresent(cn.dev33.satoken.annotation.SaCheckRole.class)).isFalse();
    }

    private void assertService(Class<?> contract, Class<?> implementation) {
        assertThat(contract.isInterface()).isTrue();
        assertThat(contract.isAssignableFrom(implementation)).isTrue();
        assertThat(implementation.getPackageName()).endsWith(".service.impl");
    }
}
