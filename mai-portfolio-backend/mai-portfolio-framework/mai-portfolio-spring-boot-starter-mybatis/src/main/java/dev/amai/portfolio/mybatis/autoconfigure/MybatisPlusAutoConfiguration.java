package dev.amai.portfolio.mybatis.autoconfigure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 统一装配 PostgreSQL 分页拦截器并限制单页最大数量。 */
@AutoConfiguration
public class MybatisPlusAutoConfiguration {
    private static final long MAX_PAGE_SIZE = 50L;

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 1. 创建拦截器链；调用方可通过同类型 Bean 覆盖自动装配。
        MybatisPlusInterceptor mybatisPlusInterceptor = new MybatisPlusInterceptor();
        // 2. 使用实际 PostgreSQL 方言和既有单页上限，作为请求层限制之外的分页兜底。
        PaginationInnerInterceptor paginationInnerInterceptor =
                new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        // 服务端限制单页上限，不能依赖调用方主动传入安全的 size。
        paginationInnerInterceptor.setMaxLimit(MAX_PAGE_SIZE);
        mybatisPlusInterceptor.addInnerInterceptor(paginationInnerInterceptor);
        return mybatisPlusInterceptor;
    }
}
