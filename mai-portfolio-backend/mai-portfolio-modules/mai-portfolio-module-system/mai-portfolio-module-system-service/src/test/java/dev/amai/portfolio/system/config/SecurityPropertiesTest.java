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
            "portfolio.security.rsa-private-key=classpath:test-private-key.pem");

    @Test
    void bindsOnlySecurityConfigurationWhileRatePoliciesBelongToAnnotations() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(SecurityProperties.class);
            assertThat(properties.allowedOrigins()).containsExactly("http://localhost");
            assertThat(properties.rsaPrivateKey()).isNotNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
