package dev.amai.portfolio.portfolio.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** project_link 持久化实体；不直接作为 HTTP 返回值。 */
@Getter
@Setter
@TableName("project_link")
public class ProjectLinkDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Integer linkType;
    private String label;
    private String url;
    private Integer isVisible;
    private Integer sortOrder;
}
