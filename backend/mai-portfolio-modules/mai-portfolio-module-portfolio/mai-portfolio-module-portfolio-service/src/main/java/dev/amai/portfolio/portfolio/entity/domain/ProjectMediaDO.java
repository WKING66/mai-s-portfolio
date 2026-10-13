package dev.amai.portfolio.portfolio.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 使用既有 project_media 表；公开授权不由 media_id 本身决定。 */
@Getter
@Setter
@TableName("project_media")
public class ProjectMediaDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long mediaId;
    private Integer role;
    private String altText;
    private Integer sortOrder;
}
