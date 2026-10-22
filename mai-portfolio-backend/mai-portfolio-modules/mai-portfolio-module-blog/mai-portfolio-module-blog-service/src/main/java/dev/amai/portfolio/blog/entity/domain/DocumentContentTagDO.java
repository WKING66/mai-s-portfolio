package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 内容独立的标签选择及展示快照，发布时随草稿提升。 */
@Getter
@Setter
@TableName("document_content_tag")
public class DocumentContentTagDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contentId;

    private Long tagId;

    private String name;

    private String slug;

    private String kind;
}
