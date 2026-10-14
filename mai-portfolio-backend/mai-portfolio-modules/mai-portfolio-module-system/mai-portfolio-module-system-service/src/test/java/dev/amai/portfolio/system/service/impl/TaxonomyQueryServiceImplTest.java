package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.amai.portfolio.system.mapper.TagMapper;
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
}
