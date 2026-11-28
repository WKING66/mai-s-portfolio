package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 博客渠道属性。读取时可附带文档授权状态用于兼容现有响应。 */
@Getter
@Setter
@TableName("blog_post")
public class BlogPostDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String slug;

    @TableField(exist = false)
    private Integer publishStatus;

    @TableField(exist = false)
    private Integer visibilityLevel;

    private Integer allowVisitorDownload;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
