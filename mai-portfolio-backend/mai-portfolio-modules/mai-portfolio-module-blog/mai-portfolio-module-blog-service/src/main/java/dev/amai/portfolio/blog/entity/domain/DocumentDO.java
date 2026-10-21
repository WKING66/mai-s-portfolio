package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 文档身份及统一写修订；草稿与正式正文由内容槽位表达。 */
@Getter
@Setter
@TableName("document")
public class DocumentDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;

    private Integer originType;

    private Long revision;

    private String createRequestId;

    private String createRequestHash;

    private String lastRequestId;

    private String lastRequestHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
