package dev.amai.portfolio.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SecurityPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "portfolio.security.allowed-origins=http://localhost",
            "portfolio.security.rsa-private-key=classpath:test-private-key.pem",
            "portfolio.security.max-login-attempts-per-client=2",
            "portfolio.security.login-attempt-window=10m");

    @Test
    void existingConfigurationGetsRegistrationDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SecurityProperties properties = context.getBean(SecurityProperties.class);
            assertThat(properties.maxRegistrationAttemptsPerClient()).isEqualTo(5);
            assertThat(properties.registrationAttemptWindow()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void registrationValuesCanDifferFromLogin() {
        contextRunner.withPropertyValues(
            "portfolio.security.max-registration-attempts-per-client=3",
            "portfolio.security.registration-attempt-window=30s")
            .run(context -> {
                assertThat(context).hasNotFailed();
                SecurityProperties properties = context.getBean(SecurityProperties.class);
                assertThat(properties.maxRegistrationAttemptsPerClient()).isEqualTo(3);
                assertThat(properties.registrationAttemptWindow()).isEqualTo(Duration.ofSeconds(30));
                assertThat(properties.maxLoginAttemptsPerClient()).isEqualTo(2);
                assertThat(properties.loginAttemptWindow()).isEqualTo(Duration.ofMinutes(10));
            });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveRegistrationLimit(int limit) {
        contextRunner.withPropertyValues(
            "portfolio.security.max-registration-attempts-per-client=" + limit)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
