package dev.amai.portfolio.system.service.impl;

import static org.mockito.Mockito.*;

import dev.amai.portfolio.system.entity.event.TagCatalogChanged;
import dev.amai.portfolio.system.mapper.TagMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TagCatalogTransactionTest {
    @Test
    void catalogOnlyExpiresAfterSuccessfulCommitAndNotOnRollback() {
        var tags = mock(TagMapper.class);
        when(tags.selectList(any())).thenReturn(List.of());
        var query = new TaxonomyQueryServiceImpl(tags);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(
                    org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean("taxonomyQueryService", TaxonomyQueryServiceImpl.class, () -> query);
            context.refresh();
            var transaction = new TransactionTemplate(new CompletionOnlyTransactionManager());

            query.tagOptions(null);
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new TagCatalogChanged());
                query.tagOptions(null);
                verify(tags).selectList(any());
            });
            query.tagOptions(null);
            verify(tags, times(2)).selectList(any());

            transaction.executeWithoutResult(status -> {
                context.publishEvent(new TagCatalogChanged());
                status.setRollbackOnly();
            });
            query.tagOptions(null);
            verify(tags, times(2)).selectList(any());
        }
    }

    /** 仅验证 Spring 的提交事件时序，不冒充数据库事务或真实数据库测试。 */
    private static class CompletionOnlyTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
