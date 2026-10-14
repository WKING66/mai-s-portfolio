package dev.amai.portfolio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.security.password.Argon2PasswordHasher;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.security.session.NamespacedSaTokenDao;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;

class SecurityAutoConfigurationTest {
    private final ApplicationContextRunner applicationContextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
                    .withBean(
                            SaTokenContextFilterForJakartaServlet.class,
                            SaTokenContextFilterForJakartaServlet::new)
                    .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                    .withBean(
                            RedisKeys.class,
                            () ->
                                    new RedisKeys(
                                            new RedisInfrastructureProperties("portfolio-test")));

    @Test
    void preservesDefaultBeanNamesAndImplementationTypes() {
        applicationContextRunner.run(
                context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(PasswordHasher.class)
                            .hasSingleBean(SaTokenDao.class)
                            .hasSingleBean(SaTokenExceptionHandler.class);
                    assertThat(context.getBean("passwordHasher"))
                            .isInstanceOf(Argon2PasswordHasher.class);
                    assertThat(context.getBean("saTokenDao"))
                            .isInstanceOf(NamespacedSaTokenDao.class);
                    assertThat(context.getBean("saTokenExceptionHandler"))
                            .isInstanceOf(SaTokenExceptionHandler.class);
                });
    }

    @Test
    void retainsRequestAndAsyncDispatchWithOfficialFilterAndOrder() {
        applicationContextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    SaTokenContextFilterForJakartaServlet saTokenContextFilter =
                            context.getBean(SaTokenContextFilterForJakartaServlet.class);
                    FilterRegistrationBean<?> saTokenContextFilterRegistration =
                            context.getBean(
                                    "saTokenContextDispatchRegistration",
                                    FilterRegistrationBean.class);
                    assertThat(saTokenContextFilterRegistration.getFilter())
                            .isSameAs(saTokenContextFilter);
                    assertThat(saTokenContextFilterRegistration.isAsyncSupported()).isTrue();
                    assertThat(saTokenContextFilterRegistration.determineDispatcherTypes())
                            .containsExactlyInAnyOrder(
                                    DispatcherType.REQUEST, DispatcherType.ASYNC);
                    assertThat(saTokenContextFilterRegistration.getOrder())
                            .isEqualTo(
                                    OrderUtils.getOrder(
                                            saTokenContextFilter.getClass(),
                                            Ordered.LOWEST_PRECEDENCE));
                });
    }

    @Test
    void allowsCallerProvidedHasherDaoAndExceptionHandler() {
        PasswordHasher customPasswordHasher = mock(PasswordHasher.class);
        SaTokenDao customSaTokenDao = mock(SaTokenDao.class);
        SaTokenExceptionHandler customSaTokenExceptionHandler = new SaTokenExceptionHandler();
        applicationContextRunner
                .withBean("customPasswordHasher", PasswordHasher.class, () -> customPasswordHasher)
                .withBean("customSaTokenDao", SaTokenDao.class, () -> customSaTokenDao)
                .withBean(
                        "customSaTokenExceptionHandler",
                        SaTokenExceptionHandler.class,
                        () -> customSaTokenExceptionHandler)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(PasswordHasher.class)
                                    .hasSingleBean(SaTokenDao.class)
                                    .hasSingleBean(SaTokenExceptionHandler.class);
                            assertThat(context.getBean(PasswordHasher.class))
                                    .isSameAs(customPasswordHasher);
                            assertThat(context.getBean(SaTokenDao.class))
                                    .isSameAs(customSaTokenDao);
                            assertThat(context.getBean(SaTokenExceptionHandler.class))
                                    .isSameAs(customSaTokenExceptionHandler);
                            assertThat(context)
                                    .doesNotHaveBean("passwordHasher")
                                    .doesNotHaveBean("saTokenDao")
                                    .doesNotHaveBean("saTokenExceptionHandler");
                        });
    }
}
