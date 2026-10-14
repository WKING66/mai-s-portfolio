package dev.amai.portfolio.logging.autoconfigure;

import dev.amai.portfolio.logging.ApiLoggingAspect;
import dev.amai.portfolio.logging.SensitivePayloadSanitizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** 自动装配 API 出入参日志及敏感字段脱敏组件。 */
@AutoConfiguration
@EnableConfigurationProperties(ApiLogProperties.class)
public class ApiLogAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SensitivePayloadSanitizer.class)
    SensitivePayloadSanitizer sensitivePayloadSanitizer(
            JsonMapper jsonMapper, ApiLogProperties properties) {
        return new SensitivePayloadSanitizer(jsonMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ApiLoggingAspect.class)
    ApiLoggingAspect apiLoggingAspect(
            SensitivePayloadSanitizer sanitizer, ApiLogProperties properties) {
        return new ApiLoggingAspect(sanitizer, properties);
    }
}
