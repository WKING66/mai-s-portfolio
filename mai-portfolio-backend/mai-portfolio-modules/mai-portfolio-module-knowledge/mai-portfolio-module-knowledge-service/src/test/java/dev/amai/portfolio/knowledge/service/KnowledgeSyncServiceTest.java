package dev.amai.portfolio.knowledge.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.document.api.DocumentKnowledgeSource;
import dev.amai.portfolio.knowledge.api.KnowledgeBuildMode;
import dev.amai.portfolio.knowledge.api.KnowledgeSourceType;
import dev.amai.portfolio.knowledge.api.KnowledgeSyncAction;
import dev.amai.portfolio.knowledge.api.KnowledgeSyncStatus;
import dev.amai.portfolio.knowledge.mapper.KnowledgeQueueMapper;
import dev.amai.portfolio.knowledge.mapper.KnowledgeQueueMapper.Row;
import dev.amai.portfolio.portfolio.api.ProfileKnowledgeSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

class KnowledgeSyncServiceTest {
    @Test
    void claimTruncatesTimestampToMicrosAndStopsIfSameRowReappears() {
        var mapper = mock(KnowledgeQueueMapper.class);
        var projections = mock(KnowledgeProjections.class);
        var schedule = mock(KnowledgeSchedule.class);
        var events = mock(ApplicationEventPublisher.class);
        var docs = mock(DocumentKnowledgeSource.class);
        var profiles = mock(ProfileKnowledgeSource.class);
        var txManager = mock(PlatformTransactionManager.class);
        var service = new KnowledgeSyncService(mapper, projections, schedule, events, docs, profiles, txManager);

        var row = new Row(
                10L, KnowledgeSourceType.DOCUMENT.code(), 1L, 1L, new byte[] {1},
                KnowledgeSyncAction.BUILD.code(), KnowledgeBuildMode.OFF_PEAK.code(),
                KnowledgeSyncStatus.PENDING.code(), Instant.EPOCH, null, null, 0, null);
        when(mapper.expired(any())).thenReturn(List.of());
        when(mapper.due(any(), anyList(), anyInt())).thenReturn(List.of(row));
        when(schedule.due(any(), eq(KnowledgeBuildMode.OFF_PEAK.code())))
                .thenAnswer(inv -> inv.getArgument(0, Instant.class).plusSeconds(60));
        when(mapper.nextDue(anyList())).thenReturn(null);

        var claims = service.claim(List.of(KnowledgeSourceType.DOCUMENT.code()), 1);

        assertThat(claims.claims()).isEmpty();
        var nowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).expired(nowCaptor.capture());
        assertThat(nowCaptor.getValue().getNano() % 1_000).isZero();
        verify(mapper, times(2)).due(eq(nowCaptor.getValue()), eq(List.of(1)), eq(1));
        verify(mapper, times(1)).update(eq(10L), eq(KnowledgeSyncStatus.PENDING.code()),
                eq(nowCaptor.getValue().plusSeconds(60)), isNull(), isNull(), eq(0), isNull());
    }
}
