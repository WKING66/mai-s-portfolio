package dev.amai.portfolio.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.amai.portfolio.logging.ApiLoggingAspect;
import dev.amai.portfolio.logging.SensitivePayloadSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class ApiLogAutoConfigurationTest {
    private final ApplicationContextRunner applicationContextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ApiLogAutoConfiguration.class))
                    .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                    .withPropertyValues(
                            "portfolio.api-log.enabled=true",
                            "portfolio.api-log.max-payload-length=2048",
                            "portfolio.api-log.additional-sensitive-fields[0]=customSecret");

    @Test
    void bindsPropertiesAndPreservesDefaultBeanNamesAndInjection() {
        applicationContextRunner.run(
                context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(ApiLogProperties.class)
                            .hasSingleBean(SensitivePayloadSanitizer.class)
                            .hasSingleBean(ApiLoggingAspect.class)
                            .hasBean("sensitivePayloadSanitizer")
                            .hasBean("apiLoggingAspect");
                    assertThat(context.getBean(ApiLogProperties.class).maxPayloadLength())
                            .isEqualTo(2048);
                    assertThat(
                                    context.getBean(SensitivePayloadSanitizer.class)
                                            .sanitize("customSecret", "sensitive-value"))
                            .isEqualTo("[REDACTED]");
                });
    }

    @Test
    void customSanitizerStillOverridesDefaultByType() {
        SensitivePayloadSanitizer customPayloadSanitizer = mock(SensitivePayloadSanitizer.class);
        applicationContextRunner
                .withBean(SensitivePayloadSanitizer.class, () -> customPayloadSanitizer)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(SensitivePayloadSanitizer.class)
                                    .hasSingleBean(ApiLoggingAspect.class);
                            assertThat(context.getBean(SensitivePayloadSanitizer.class))
                                    .isSameAs(customPayloadSanitizer);
                        });
    }

    @Test
    void retainsConfiguredPayloadLengthBoundaries() {
        for (int validLength : new int[] {256, 16384}) {
            applicationContextRunner
                    .withPropertyValues("portfolio.api-log.max-payload-length=" + validLength)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(
                                                context.getBean(ApiLogProperties.class)
                                                        .maxPayloadLength())
                                        .isEqualTo(validLength);
                            });
        }
        for (int invalidLength : new int[] {255, 16385}) {
            applicationContextRunner
                    .withPropertyValues("portfolio.api-log.max-payload-length=" + invalidLength)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
