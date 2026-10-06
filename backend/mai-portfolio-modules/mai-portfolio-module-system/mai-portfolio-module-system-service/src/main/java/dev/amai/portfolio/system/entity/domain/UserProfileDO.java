package dev.amai.portfolio.system.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 账号本人的个人资料，不对应首页站长展示资料。 */
@Getter
@Setter
@TableName("user_profile")
public class UserProfileDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    // 显式空昵称表示恢复用户名，不能被 MyBatis-Plus 的默认非空更新策略忽略。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String nickname;
    private Long avatarMediaId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
