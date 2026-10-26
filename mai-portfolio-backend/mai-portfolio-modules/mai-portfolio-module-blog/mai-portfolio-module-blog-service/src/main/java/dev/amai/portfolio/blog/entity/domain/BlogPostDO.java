package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 博客身份及公开状态；正式正文由 Document 的 PUBLISHED 槽位提供。 */
@Getter
@Setter
@TableName("blog_post")
public class BlogPostDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String slug;

    private Integer status;

    private Integer allowVisitorDownload;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
