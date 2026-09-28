package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.auth.AdminAuthorization;
import dev.amai.portfolio.auth.AccountRoleProvider;
import dev.amai.portfolio.config.BootstrapRunner;
import dev.amai.portfolio.controller.AdminSessionController;
import dev.amai.portfolio.controller.ProfileController;
import dev.amai.portfolio.service.AdminSessionService;
import dev.amai.portfolio.service.BootstrapService;
import dev.amai.portfolio.service.OwnerAccessService;
import dev.amai.portfolio.service.OwnerRoleService;
import dev.amai.portfolio.service.PasswordService;
import dev.amai.portfolio.service.ProfileService;
import dev.amai.portfolio.service.LoginChallengeService;
import dev.amai.portfolio.service.impl.AdminSessionServiceImpl;
import dev.amai.portfolio.service.impl.BootstrapServiceImpl;
import dev.amai.portfolio.service.impl.OwnerAccessServiceImpl;
import dev.amai.portfolio.service.impl.OwnerRoleServiceImpl;
import dev.amai.portfolio.service.impl.PasswordServiceImpl;
import dev.amai.portfolio.service.impl.ProfileServiceImpl;
import dev.amai.portfolio.service.impl.LoginChallengeServiceImpl;
import org.junit.jupiter.api.Test;

class ServiceArchitectureTest {
    @Test
    void servicesAreInterfacesWithDedicatedImplementations() {
        assertService(ProfileService.class, ProfileServiceImpl.class);
        assertService(AdminSessionService.class, AdminSessionServiceImpl.class);
        assertService(OwnerAccessService.class, OwnerAccessServiceImpl.class);
        assertService(OwnerRoleService.class, OwnerRoleServiceImpl.class);
        assertService(PasswordService.class, PasswordServiceImpl.class);
        assertService(BootstrapService.class, BootstrapServiceImpl.class);
        assertService(LoginChallengeService.class, LoginChallengeServiceImpl.class);
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
        assertThat(BootstrapRunner.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(BootstrapService.class);
    }

    private void assertService(Class<?> contract, Class<?> implementation) {
        assertThat(contract.isInterface()).isTrue();
        assertThat(contract.isAssignableFrom(implementation)).isTrue();
        assertThat(implementation.getPackageName()).endsWith(".service.impl");
    }
}
