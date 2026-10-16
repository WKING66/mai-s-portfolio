package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

/** 版本资源关系；Java 字段与文档资源语义一致，旧表列名通过 TableField 保留，失败时允许不绑定资产。 */
@Getter
@Setter
@TableName("document_version_asset")
public class DocumentResourceDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentVersionId;

    @TableField("media_id")
    private Long assetId;

    private Integer role;

    private String altText;

    @TableField("source_reference")
    private String originalUri;

    private Integer sortOrder;

    @TableField("resource_key")
    private String key;

    @TableField("resource_type")
    private Integer type;

    @TableField("source_kind")
    private Integer source;

    private Integer status;

    private String errorMessage;
}
