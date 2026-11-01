package dev.amai.portfolio.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.blog.entity.domain.BlogPostDO;
import dev.amai.portfolio.blog.entity.dto.PublishedBlogData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BlogPostMapper extends BaseMapper<BlogPostDO> {
    /** 按视图在数据库筛选可见标题/标识，再分页；不会将一篇文章的两个槽位重复计数。 */
    Page<BlogPostDO> selectListPage(
            Page<BlogPostDO> page,
            @Param("status") Integer status,
            @Param("keyword") String keyword,
            @Param("manage") boolean manage,
            @Param("draftStage") int draftStage,
            @Param("publishedStage") int publishedStage);

    /** 按大小写不敏感的公开标识检查其他文章；更新必须排除当前身份。 */
    boolean existsOtherSlug(@Param("slug") String slug, @Param("id") Long id);

    /** 只读取指定 slug 的上架正文，不与草稿或多条资源一起 JOIN 放大正文行。 */
    PublishedBlogData selectPublishedArticle(
            @Param("slug") String slug,
            @Param("publishedStatus") int publishedStatus,
            @Param("publishedStage") int publishedStage);
}
