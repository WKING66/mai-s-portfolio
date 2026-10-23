package dev.amai.portfolio.blog.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.blog.entity.domain.DocumentImportTaskDO;
import dev.amai.portfolio.blog.enums.ImportTaskStatus;
import dev.amai.portfolio.blog.mapper.DocumentImportTaskMapper;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import dev.amai.portfolio.common.PageRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DocumentImportTaskServiceImplTest {
    private final DocumentImportTaskMapper documentImportTaskMapper = mock(DocumentImportTaskMapper.class);
    private final DocumentImportTaskServiceImpl service =
            new DocumentImportTaskServiceImpl(documentImportTaskMapper);

    @BeforeAll
    static void initializeMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "import-task-test"),
                DocumentImportTaskDO.class);
    }

    @Test
    void pageKeepsRequestedWindowTotalAndExternalStatusName() {
        var task = task();
        when(documentImportTaskMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<DocumentImportTaskDO> result = invocation.getArgument(0);
            assertThat(result.getCurrent()).isEqualTo(2);
            assertThat(result.getSize()).isEqualTo(12);
            return result.setTotal(31).setRecords(List.of(task));
        });

        var result = service.page(7L, new PageRequest(2, 12));

        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(12);
        assertThat(result.getTotal()).isEqualTo(31);
        assertThat(result.getItems()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(8);
            assertThat(item.status()).isEqualTo("SUCCEEDED_WITH_ISSUES");
        });
        verify(documentImportTaskMapper).selectPage(any(Page.class), any());
        verifyNoMoreInteractions(documentImportTaskMapper);
    }

    @Test
    void getProjectsOneRecordAndMissingTaskIsNotFound() {
        when(documentImportTaskMapper.selectById(8L)).thenReturn(task());
        assertThat(service.get(8).status()).isEqualTo("SUCCEEDED_WITH_ISSUES");
        assertThatThrownBy(() -> service.get(9)).isInstanceOf(ApiException.class);
    }

    @Test
    void beginStillChecksExistingRequestBeforeWriting() {
        var previous = task();
        when(documentImportTaskMapper.selectOne(any())).thenReturn(previous);
        assertThatThrownBy(() -> service.begin(7, "existing", "article.md", 10))
                .isInstanceOf(ApiException.class).hasMessageContaining("taskId=8");
        verify(documentImportTaskMapper, never()).insert(any(DocumentImportTaskDO.class));
    }

    @Test
    void numericPersistenceCodesStayDistinctFromApiStatusNames() {
        for (ImportTaskStatus status : ImportTaskStatus.values()) {
            var task = task();
            task.setStatus(status.code());
            when(documentImportTaskMapper.selectById(8L)).thenReturn(task);
            assertThat(service.get(8).status()).isEqualTo(status.name());
        }
        assertThatThrownBy(() -> ImportTaskStatus.fromCode(99)).isInstanceOf(IllegalArgumentException.class);
    }

    private DocumentImportTaskDO task() {
        var task = new DocumentImportTaskDO();
        task.setId(8L);
        task.setDocumentId(7L);
        task.setStatus(ImportTaskStatus.SUCCEEDED_WITH_ISSUES.code());
        task.setSourceSize(10L);
        return task;
    }
}
