package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 内容资源关系；删除关系不等于删除共享 Asset。 */
@Getter
@Setter
@TableName("document_resource")
public class DocumentResourceDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contentId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long assetId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String originalUri;

    private Integer sortOrder;

    @TableField("resource_key")
    private String key;

    @TableField("resource_type")
    private Integer type;

    @TableField("source_kind")
    private Integer source;

    private Integer status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
}
