package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 当前内容槽位，不保存历史版本。 */
@Getter
@Setter
@TableName("document_content")
public class DocumentContentDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private String stage;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String title;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    private String content;

    private Short readingTimeMinutes;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
