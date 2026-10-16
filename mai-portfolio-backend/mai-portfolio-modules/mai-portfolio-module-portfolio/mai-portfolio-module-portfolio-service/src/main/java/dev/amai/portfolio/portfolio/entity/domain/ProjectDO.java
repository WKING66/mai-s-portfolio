package dev.amai.portfolio.portfolio.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** project 持久化实体；不直接作为 HTTP 返回值。 */
@Getter
@Setter
@TableName("project")
public class ProjectDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String slug;

    private String title;

    private String summary;

    private String contribution;

    private String outcome;

    private String timeLabel;

    private Integer status;

    private Integer isFeatured;

    private Integer sortOrder;

    private Long version;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
