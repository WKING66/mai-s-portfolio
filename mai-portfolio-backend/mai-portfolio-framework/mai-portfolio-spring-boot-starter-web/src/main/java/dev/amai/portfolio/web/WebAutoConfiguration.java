package dev.amai.portfolio.web;

import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 装配所有业务应用共用的 Web 异常响应能力。 */
@AutoConfiguration
public class WebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
