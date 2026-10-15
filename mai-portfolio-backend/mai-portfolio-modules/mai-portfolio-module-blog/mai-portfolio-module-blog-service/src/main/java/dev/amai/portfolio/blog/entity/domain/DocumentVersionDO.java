package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

/** 不可变的正文、标题与摘要快照；新增保存追加记录，公开发布只改变博客指针。 */
@Getter
@Setter
@TableName("document_version")
public class DocumentVersionDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long versionNumber;

    private String title;

    private String summary;

    private String markdownContent;

    private Short readingTimeMinutes;

    private Long createdBy;

    private java.time.LocalDateTime createdAt;
}
