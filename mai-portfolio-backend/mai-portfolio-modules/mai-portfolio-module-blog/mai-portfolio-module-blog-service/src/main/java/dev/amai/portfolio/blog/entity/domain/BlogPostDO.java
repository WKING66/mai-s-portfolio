package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 发布容器；一篇博客关联一个 Document，公开访问固定读取 publishedVersionId。 */
@Getter
@Setter
@TableName("blog_post")
public class BlogPostDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long publishedVersionId;

    private String slug;

    private Integer status;

    private Integer allowVisitorDownload;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
