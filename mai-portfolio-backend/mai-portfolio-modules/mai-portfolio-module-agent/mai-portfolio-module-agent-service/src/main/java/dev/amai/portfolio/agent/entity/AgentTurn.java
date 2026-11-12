package dev.amai.portfolio.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
    @TableId(type = IdType.INPUT)
    private String requestId;

    private String conversationId;

    private String executionId;

    private String question;

    private String answer;

    private AgentTurnState status;

    private String errorCode;

    private OffsetDateTime acceptBefore;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long ordinal;

    public AgentTurn(String requestId, String conversationId, String executionId, String question,
            String answer, AgentTurnState status, String errorCode, OffsetDateTime acceptBefore) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.executionId = executionId;
        this.question = question;
        this.answer = answer;
        this.status = status;
        this.errorCode = errorCode;
        this.acceptBefore = acceptBefore;
    }
}
