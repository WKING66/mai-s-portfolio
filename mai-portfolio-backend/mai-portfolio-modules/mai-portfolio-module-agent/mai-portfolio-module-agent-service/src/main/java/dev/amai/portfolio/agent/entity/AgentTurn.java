package dev.amai.portfolio.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import dev.amai.portfolio.agent.enums.AgentTurnState;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("agent_turn")
public class AgentTurn {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long conversationId;

    private Long executionId;

    private String question;

    private String answer;

    private AgentTurnState status;

    private String errorMes;

    private Long inputTokens;

    private Long outputTokens;

    private Integer usageStatus;

    private OffsetDateTime acceptBefore;

    private OffsetDateTime quotaAt;

    @TableField("is_deleted")
    private boolean deleted;

    public AgentTurn(String requestId, Long conversationId, Long executionId, String question,
            String answer, AgentTurnState status, String errorMes, OffsetDateTime acceptBefore) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.executionId = executionId;
        this.question = question;
        this.answer = answer;
        this.status = status;
        this.errorMes = errorMes;
        this.acceptBefore = acceptBefore;
    }
}
