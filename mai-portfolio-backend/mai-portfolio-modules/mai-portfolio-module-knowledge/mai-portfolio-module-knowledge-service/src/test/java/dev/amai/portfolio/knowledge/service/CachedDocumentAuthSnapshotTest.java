package dev.amai.portfolio.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.blog.api.BlogKnowledgeSource;
import dev.amai.portfolio.document.api.DocumentKnowledgeSource;
import dev.amai.portfolio.knowledge.api.KnowledgeSourceChanged;
import dev.amai.portfolio.knowledge.api.KnowledgeSourceType;
import dev.amai.portfolio.knowledge.mapper.KnowledgeQueueMapper;
import dev.amai.portfolio.portfolio.api.ProfileKnowledgeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CachedDocumentAuthSnapshotTest {
    @Test
    void aLateSnapshotCannotRestoreRevokedAuthorization() throws Exception {
        var documents = mock(DocumentKnowledgeSource.class);
        var blogs = mock(BlogKnowledgeSource.class);
        var profiles = mock(ProfileKnowledgeSource.class);
        var queue = mock(KnowledgeQueueMapper.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var formal = new DocumentKnowledgeSource.Formal(42, "Formal", null, "# Formal", List.of());
        when(documents.formalSnapshot(List.of(42L))).thenReturn(List.of(formal));
        when(documents.accessSnapshot()).thenReturn(List.of(new DocumentKnowledgeSource.Access(42, 1, 1, true)),
                List.of(new DocumentKnowledgeSource.Access(42, 2, 1, true)));
        var before = new KnowledgeQueueMapper.Row(1, 1, 42, 1, KnowledgeProjections.document(formal, null).hash(), 1, 1, 3,
                Instant.EPOCH, null, null, 1, null);
        var after = new KnowledgeQueueMapper.Row(1, 1, 42, 2, null, 2, 1, 0,
                Instant.EPOCH, null, null, 0, null);
        var loading = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(blogs.metadataSnapshot()).thenAnswer(call -> {
            loading.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of();
        }).thenReturn(List.of());
        when(queue.all()).thenReturn(List.of(before)).thenReturn(List.of(after));
        var cache = new CachedDocumentAuthSnapshot(queue, documents, blogs, profiles, transactions, Duration.ofHours(12));
        var candidates = List.of(new CachedDocumentAuthSnapshot.Candidate(1, 42, 1));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> cache.validate(candidates).getFirst());
            try {
                assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
                cache.invalidate(new KnowledgeSourceChanged(KnowledgeSourceType.DOCUMENT, 42));
                assertThat(cache.validate(candidates).getFirst().authorized()).isFalse();
            } finally {
                finish.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).authorized()).isFalse();
            assertThat(cache.validate(candidates).getFirst().currentChangeSeq()).isEqualTo(2);
        }
    }
}
