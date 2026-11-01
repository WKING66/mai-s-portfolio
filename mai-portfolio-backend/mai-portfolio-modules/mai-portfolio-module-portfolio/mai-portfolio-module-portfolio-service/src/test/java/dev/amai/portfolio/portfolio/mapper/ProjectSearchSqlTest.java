package dev.amai.portfolio.portfolio.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.HashMap;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.Test;

class ProjectSearchSqlTest {
    @Test
    void literalKeywordAndStatusAreBoundAndIncludedInCount() throws Exception {
        var configuration = new MybatisConfiguration();
        try (var input = getClass().getResourceAsStream("/mapper/ProjectMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/ProjectMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("keyword", "x%'_\\");
        parameters.put("status", 0);
        String query = configuration.getMappedStatement(ProjectMapper.class.getName() + ".selectManagementPage")
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
        assertThat(query).contains("p.status = ?", "LOWER(p.title)", "LOWER(p.slug)", "LOWER(?)")
                .doesNotContain("x%'_", " LIKE ")
                .contains("ORDER BY p.is_featured DESC, p.sort_order ASC, p.id DESC");
        String count = new PaginationInnerInterceptor(DbType.POSTGRE_SQL).autoCountSql(new Page<>(), query);
        assertThat(count.toLowerCase()).contains("count(", "p.status", "p.title", "p.slug")
                .doesNotContain("order by");
    }
}
