package dev.amai.portfolio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_account")
public class UserAccountEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private Integer type;
    private String passwordHash;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
