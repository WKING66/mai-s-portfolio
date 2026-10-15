package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

/** 文档身份及最新保存指针；currentVersionId 不能用来判断访客当前应看到哪个版本。 */
@Getter
@Setter
@TableName("document")
public class DocumentDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;

    private Integer originType;

    private Long currentVersionId;

    private Long lockVersion;

    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime updatedAt;
}
