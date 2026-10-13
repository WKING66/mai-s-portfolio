package dev.amai.portfolio.portfolio.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** project_tag 持久化实体；不直接作为 HTTP 返回值。 */
@Getter
@Setter
@TableName("project_tag")
public class ProjectTagDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long tagId;
}
