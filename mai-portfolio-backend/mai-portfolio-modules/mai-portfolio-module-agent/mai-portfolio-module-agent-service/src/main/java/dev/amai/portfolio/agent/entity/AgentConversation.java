package dev.amai.portfolio.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_conversation")
public class AgentConversation {
    @TableId(type = IdType.INPUT)
    private String id;

    private String ownerKey;

    private boolean anonymous;

    private String importId;

    private OffsetDateTime updatedAt;
}
