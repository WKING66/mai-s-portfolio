package dev.amai.portfolio.datasource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;

/** 装配数据访问异常的统一 HTTP 兜底响应。 */
@AutoConfiguration
@ConditionalOnClass(DataIntegrityViolationException.class)
public class DatasourceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DataAccessExceptionHandler.class)
    DataAccessExceptionHandler dataAccessExceptionHandler() {
        return new DataAccessExceptionHandler();
    }
}
