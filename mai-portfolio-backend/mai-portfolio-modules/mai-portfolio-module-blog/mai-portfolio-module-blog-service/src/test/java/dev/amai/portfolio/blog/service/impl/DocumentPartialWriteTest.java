package dev.amai.portfolio.blog.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.ContentStage;
import dev.amai.portfolio.blog.mapper.*;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentPartialWriteTest {
    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private final DocumentContentMapper contentMapper = mock(DocumentContentMapper.class);
    private final DocumentResourceMapper resourceMapper = mock(DocumentResourceMapper.class);
    private final DocumentContentTagMapper tagMapper = mock(DocumentContentTagMapper.class);
    private final AssetQueryService assets = mock(AssetQueryService.class);
    private final DocumentServiceImpl service = new DocumentServiceImpl(
            documentMapper, contentMapper, resourceMapper, tagMapper, assets);

    @BeforeAll
    static void initializeMetadata() {
        var builder = new MapperBuilderAssistant(new MybatisConfiguration(), "scoped-document-write");
        TableInfoHelper.initTableInfo(builder, DocumentDO.class);
        TableInfoHelper.initTableInfo(builder, DocumentContentDO.class);
        TableInfoHelper.initTableInfo(builder, DocumentResourceDO.class);
        TableInfoHelper.initTableInfo(builder, DocumentContentTagDO.class);
    }

    @Test
    void bodyUpdateDoesNotIncludePropertyColumnsOrWriteTags() {
        service.saveContent(current(true), new PreparedDocument("changed", List.of(), Set.of()), 7);
        var update = update();
        assertThat(update.getSqlSet()).contains("content=", "reading_time_minutes=")
                .doesNotContain("title=", "summary=");
        verifyNoInteractions(tagMapper, documentMapper);
        verify(contentMapper, never()).insert(any(DocumentContentDO.class));
    }

    @Test
    void propertiesUpdateDoesNotReadAssetsOrRewriteBodyAndResources() {
        service.saveProperties(current(true), "Changed", "summary", List.of(), 7);
        assertThat(update().getSqlSet()).contains("title=", "summary=")
                .doesNotContain("content=", "reading_time_minutes=");
        verifyNoInteractions(assets, resourceMapper, documentMapper);
        verify(contentMapper, never()).insert(any(DocumentContentDO.class));
    }

    @Test
    void clearingTitleAndSummaryWritesExplicitNullsWithoutClearingContent() {
        service.saveProperties(current(true), null, null, List.of(), 7);
        var update = update();
        assertThat(update.getSqlSet()).contains("title=", "summary=").doesNotContain("content=");
        assertThat(update.getParamNameValuePairs()).containsValue(null);
    }

    @Test
    void firstPropertyEditCopiesFormalContentAndUsesNewDraftIdentity() {
        var formal = new DocumentContentDO();
        formal.setId(10L);
        formal.setDocumentId(9L);
        formal.setStage(ContentStage.PUBLISHED.code());
        formal.setTitle("Before");
        formal.setSummary("summary");
        formal.setContent("stored");
        formal.setReadingTimeMinutes((short) 1);
        when(contentMapper.selectById(10L)).thenReturn(formal);
        doAnswer(call -> {
            ((DocumentContentDO) call.getArgument(0)).setId(30L);
            return 1;
        }).when(contentMapper).insert(any(DocumentContentDO.class));
        service.saveProperties(current(false), "Changed", "summary", List.of(), 7);
        var draft = ArgumentCaptor.forClass(DocumentContentDO.class);
        verify(contentMapper).insert(draft.capture());
        assertThat(draft.getValue().getStage()).isEqualTo(ContentStage.DRAFT.code());
        assertThat(draft.getValue().getContent()).isEqualTo("stored");
        var update = update();
        update.getSqlSegment();
        assertThat(update.getParamNameValuePairs()).containsValue(30L).doesNotContainValue(10L);
        verify(contentMapper, never()).updateById(any(DocumentContentDO.class));
        verifyNoInteractions(assets);
    }

    @Test
    void staleRevisionHasDedicatedCodeBeforeAnyContentWrite() {
        var identity = new DocumentDO();
        identity.setId(9L);
        identity.setRevision(6L);
        when(documentMapper.selectOne(any())).thenReturn(identity);
        assertThatThrownBy(() -> service.beginWrite(9, 5, "new", "hash"))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.DOCUMENT_REVISION_CONFLICT));
        verifyNoInteractions(contentMapper, resourceMapper, tagMapper, assets);
    }

    @Test
    void reusedRequestWithChangedPayloadHasDedicatedCodeAndDoesNotWrite() {
        var identity = new DocumentDO();
        identity.setId(9L);
        identity.setRevision(6L);
        identity.setLastRequestId("saved");
        identity.setLastRequestHash("body-hash");
        when(documentMapper.selectOne(any())).thenReturn(identity);
        assertThatThrownBy(() -> service.beginWrite(9, 5, "saved", "properties-hash"))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.REQUEST_ID_CONFLICT));
        verifyNoInteractions(contentMapper, resourceMapper, tagMapper, assets);
    }

    private Document current(boolean draft) {
        return new Document(9, 10, 5, draft ? ContentStage.DRAFT : ContentStage.PUBLISHED,
                draft, true, "Before", "summary", "stored", List.of(), List.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LambdaUpdateWrapper<DocumentContentDO> update() {
        var capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(contentMapper).update(isNull(), capture.capture());
        return capture.getValue();
    }
}
