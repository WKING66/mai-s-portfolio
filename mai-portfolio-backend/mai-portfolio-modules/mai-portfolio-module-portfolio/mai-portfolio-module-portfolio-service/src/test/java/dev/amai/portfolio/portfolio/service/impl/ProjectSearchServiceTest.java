package dev.amai.portfolio.portfolio.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.common.ContentListRequest;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import dev.amai.portfolio.portfolio.mapper.ProjectMapper;
import dev.amai.portfolio.web.exception.ApiException;
import org.junit.jupiter.api.Test;

class ProjectSearchServiceTest {
    private final ProjectMapper mapper = mock(ProjectMapper.class);
    private final ProjectServiceImpl service = new ProjectServiceImpl(mapper, null, null, null, null, null);

    @Test
    void managePassesNormalizedKeywordStatusAndPaginationToDatabase() {
        var input = new ContentListRequest("MANAGE", 2, 5, "PUBLISHED");
        input.setKeyword("  C++%_  ");
        when(mapper.selectManagementPage(any(), eq(1), eq("C++%_")))
                .thenAnswer(call -> ((Page<ProjectDO>) call.getArgument(0)).setTotal(17));
        var result = service.list(input);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(17);
        assertThat(result.getSize()).isEqualTo(5);
        verify(mapper, never()).selectPage(any(), any());
    }

    @Test
    void noSearchKeepsManageDefaultSize() {
        when(mapper.selectManagementPage(any(), isNull(), isNull()))
                .thenAnswer(call -> call.getArgument(0));
        assertThat(service.list(new ContentListRequest("MANAGE", 1, null, null)).getSize()).isEqualTo(20);
    }

    @Test
    void publicCannotFilterPrivateContent() {
        var input = new ContentListRequest("PUBLIC", 1, 5, null);
        input.setKeyword("draft");
        assertThatThrownBy(() -> service.list(input)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(new ContentListRequest("PUBLIC", 1, 5, "DRAFT")))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidStatusIsRejectedBeforeDatabaseQuery() {
        assertThatThrownBy(() -> service.list(new ContentListRequest("MANAGE", 1, 5, "unknown")))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }
}
