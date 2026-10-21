package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.amai.portfolio.system.mapper.TagMapper;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxonomyQueryServiceImplTest {
    private final TagMapper tags = mock(TagMapper.class);
    private final TaxonomyQueryServiceImpl service = new TaxonomyQueryServiceImpl(tags);
    @Test void unsupportedSelectionKindsAreRejectedBeforeQuery() {
        for (String kind : new String[] {"TOPIC", "INVALID", "tech", ""}) {
            assertThatThrownBy(() -> service.techTagOptions(kind)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        }
        verifyNoInteractions(tags);
    }
    @Test void existingTechnicalTagProviderKeepsItsContract() {
        when(tags.selectList(any())).thenReturn(List.of());
        assertThat(service.techTagOptions()).isEmpty();
        assertThat(service.techTagOptions("TECH")).isEmpty();
        verify(tags, times(2)).selectList(any());
    }

    @Test
    void genericOptionsKeepTopicSeparateFromTechnicalGroups() {
        var topic = new TagDO();
        topic.setId(1L);
        topic.setKind(0);
        topic.setName("主题");
        topic.setSlug("topic");
        var tech = new TagDO();
        tech.setId(2L);
        tech.setKind(1);
        tech.setName("Java");
        tech.setSlug("java");
        tech.setGroupCode(0);
        when(tags.selectList(any())).thenReturn(List.of(topic, tech));

        var result = service.tagOptions(null);

        assertThat(result).extracting(tag -> tag.kind()).containsExactly("TOPIC", "TECH");
        assertThat(result.getFirst().group()).isNull();
        assertThat(result.getLast().group()).isEqualTo("LANGUAGE");
    }

    @Test
    void selectedTagsUseOneBatchAndRejectMissingOrDuplicateIds() {
        var topic = new TagDO();
        topic.setId(1L);
        topic.setKind(0);
        topic.setName("主题");
        topic.setSlug("topic");
        when(tags.selectByIds(anyCollection())).thenReturn(List.of(topic));

        assertThat(service.requireTags(List.of(1L))).extracting(tag -> tag.id()).containsExactly(1L);
        assertThatThrownBy(() -> service.requireTags(List.of(1L, 1L))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireTags(List.of(1L, 2L))).isInstanceOf(ApiException.class);
        verify(tags, times(2)).selectByIds(anyCollection());
    }
}
