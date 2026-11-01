package dev.amai.portfolio.blog.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.Test;

class BlogSearchSqlTest {
    private String sql(String keyword, Integer status) throws Exception {
        var configuration = new MybatisConfiguration();
        try (var input = getClass().getResourceAsStream("/mapper/BlogPostMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/BlogPostMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("keyword", keyword);
        parameters.put("status", status);
        parameters.put("manage", true);
        parameters.put("draftStage", 0);
        parameters.put("publishedStage", 1);
        return configuration.getMappedStatement(BlogPostMapper.class.getName() + ".selectListPage")
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
    }

    @Test
    void draftExistenceWinsEvenWhenItsTitleIsNullAndKeywordIsBound() throws Exception {
        String query = sql("x%'_\\", 0);
        assertThat(query).contains("CASE WHEN draft.id IS NOT NULL THEN draft.title ELSE published.title END")
                .contains("b.status = ?", "LOWER(?)", "strpos(")
                .doesNotContain("COALESCE", "x%'_", " LIKE ");
        assertThat(query).contains("ORDER BY b.published_at DESC, b.id DESC");
    }

    @Test
    void noSearchDoesNotJoinOrReadDocumentContent() throws Exception {
        assertThat(sql(null, null)).doesNotContain("JOIN", "document_content", "WHERE")
                .contains("ORDER BY b.published_at DESC, b.id DESC");
    }

    @Test
    void optimizedCountRetainsTheSameTitleSelectionAndFilters() throws Exception {
        String count = new PaginationInnerInterceptor(DbType.POSTGRE_SQL)
                .autoCountSql(new Page<>(), sql("algorithm", 1));
        assertThat(count.toLowerCase()).contains("count(", "draft.id", "draft.title", "published.title", "b.status")
                .doesNotContain("order by");
    }
}
