package dev.amai.portfolio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("tag")
public class TagEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer kind;
    private String name;
    private String normalizedName;
    private String slug;
    private Integer groupCode;
    private String logoKey;
    private Integer isFeatured;
    private Integer sortOrder;
}
