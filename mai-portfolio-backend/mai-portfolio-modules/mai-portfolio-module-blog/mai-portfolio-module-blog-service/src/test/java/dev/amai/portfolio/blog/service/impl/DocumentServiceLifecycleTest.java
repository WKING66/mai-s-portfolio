package dev.amai.portfolio.blog.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.mapper.*;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentServiceLifecycleTest {
    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final DocumentContentMapper documentContentMapper = mock(DocumentContentMapper.class);
    private final DocumentResourceMapper documentResourceMapper = mock(DocumentResourceMapper.class);
    private final DocumentContentTagMapper documentContentTagMapper = mock(DocumentContentTagMapper.class);
    private final DocumentServiceImpl documentService = new DocumentServiceImpl(documentMapper,
            documentContentMapper, documentResourceMapper, documentContentTagMapper,
            mock(AssetQueryService.class));

    @Test
    void staleWriteCannotChangeContent() {
        var identity = identity();
        when(documentMapper.selectOne(any())).thenReturn(identity);

        assertThatThrownBy(() -> documentService.beginWrite(7, 4, "next", "hash"))
                .isInstanceOf(ApiException.class).hasMessageContaining("已被修改");
        verifyNoInteractions(documentContentMapper, documentResourceMapper, documentContentTagMapper);
    }

    @Test
    void committedRetryOnlyAcceptsSamePayload() {
        var identity = identity();
        identity.setLastRequestId("saved-request");
        identity.setLastRequestHash("saved-hash");
        when(documentMapper.selectOne(any())).thenReturn(identity);

        assertThat(documentService.beginWrite(7, 4, "saved-request", "saved-hash")).isFalse();
        assertThatThrownBy(() -> documentService.beginWrite(7, 4, "saved-request", "changed-hash"))
                .isInstanceOf(ApiException.class).hasMessageContaining("不同内容");
        verifyNoInteractions(documentContentMapper, documentResourceMapper, documentContentTagMapper);
    }

    @Test
    void wholeDeletionWithFormalAndDraftRequiresHandlingDraftFirst() {
        when(documentContentMapper.selectList(any())).thenReturn(
                List.of(content(10, "PUBLISHED"), content(11, "DRAFT")));

        assertThatThrownBy(() -> documentService.delete(7))
                .isInstanceOf(ApiException.class).hasMessageContaining("草稿");
        verify(documentMapper, never()).deleteById(anyLong());
        verifyNoInteractions(documentResourceMapper, documentContentTagMapper);
    }

    @Test
    void draftOnlyArticleMustUseWholeDelete() {
        when(documentContentMapper.selectList(any())).thenReturn(List.of(content(11, "DRAFT")));

        assertThatThrownBy(() -> documentService.discardDraft(7))
                .isInstanceOf(ApiException.class).hasMessageContaining("整篇删除");
        verify(documentContentMapper, never()).deleteById(anyLong());
    }

    @Test
    void discardOnlyRemovesDraftContent() {
        when(documentContentMapper.selectList(any())).thenReturn(
                List.of(content(10, "PUBLISHED"), content(11, "DRAFT")));

        documentService.discardDraft(7);

        verify(documentContentMapper).deleteById(11L);
        verify(documentContentMapper, never()).deleteById(10L);
        verify(documentResourceMapper).delete(any());
        verify(documentContentTagMapper).delete(any());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void publishingPromotesSavedDraftAndRemovesOldFormalRelations() {
        var draft = content(11, "DRAFT");
        when(documentContentMapper.selectOne(any())).thenReturn(draft, content(10, "PUBLISHED"));

        documentService.publish(7);

        verify(documentContentMapper).deleteById(10L);
        var replacement = ArgumentCaptor.forClass(DocumentContentDO.class);
        verify(documentContentMapper).updateById(replacement.capture());
        assertThat(replacement.getValue().getId()).isEqualTo(11);
        assertThat(replacement.getValue().getStage()).isEqualTo("PUBLISHED");
        verify(documentResourceMapper).delete(any());
        verify(documentContentTagMapper).delete(any());
        verify(documentMapper, never()).deleteById(anyLong());
    }

    private DocumentDO identity() {
        var identity = new DocumentDO();
        identity.setId(7L);
        identity.setRevision(5L);
        return identity;
    }

    private DocumentContentDO content(long id, String stage) {
        var content = new DocumentContentDO();
        content.setId(id);
        content.setDocumentId(7L);
        content.setStage(stage);
        return content;
    }
}
