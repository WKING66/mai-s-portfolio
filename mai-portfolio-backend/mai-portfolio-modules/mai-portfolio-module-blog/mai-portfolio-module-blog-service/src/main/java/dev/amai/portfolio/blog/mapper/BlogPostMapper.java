package dev.amai.portfolio.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.blog.entity.domain.BlogPostDO;
import dev.amai.portfolio.blog.entity.dto.PublishedBlogData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BlogPostMapper extends BaseMapper<BlogPostDO> {
    /** 只读取指定 slug 的上架正文，不与草稿或多条资源一起 JOIN 放大正文行。 */
    PublishedBlogData selectPublishedArticle(
            @Param("slug") String slug,
            @Param("publishedStatus") int publishedStatus,
            @Param("publishedStage") int publishedStage);
}
