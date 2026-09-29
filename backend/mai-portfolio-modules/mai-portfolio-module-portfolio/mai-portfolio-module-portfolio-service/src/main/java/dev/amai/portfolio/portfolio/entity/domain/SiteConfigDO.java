package dev.amai.portfolio.portfolio.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("site_config")
public class SiteConfigDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String displayName;
    private String headline;
    private String intro;
    private String githubUrl;
    private String email;
    private Long avatarMediaId;
    private Long resumeMediaId;
    private String seoTitle;
    private String seoDescription;
    private LocalDateTime updatedAt;
}
