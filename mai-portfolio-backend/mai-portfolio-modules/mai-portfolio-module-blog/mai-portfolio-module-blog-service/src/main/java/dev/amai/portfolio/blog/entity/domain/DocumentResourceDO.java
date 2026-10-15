package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

/** 版本资源关系；表及 mediaId 沿用旧命名，实际关联统一 media_asset，失败时允许为空。 */
@Getter
@Setter
@TableName("document_version_asset")
public class DocumentResourceDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentVersionId;

    private Long mediaId;

    private Integer role;

    private String altText;

    private String sourceReference;

    private Integer sortOrder;

    private String resourceKey;

    private Integer resourceType;

    private Integer sourceKind;

    private Integer status;
}
