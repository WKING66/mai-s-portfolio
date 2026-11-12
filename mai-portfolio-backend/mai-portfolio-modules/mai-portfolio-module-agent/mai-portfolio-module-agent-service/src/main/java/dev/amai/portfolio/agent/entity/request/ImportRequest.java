package dev.amai.portfolio.agent.entity.request;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IDENTIFIER_PATTERN;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IMPORT_TURN_LIMIT;

import dev.amai.portfolio.agent.service.AgentConversationService.ImportedTurn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 导入已有问答历史的请求。 */
public record ImportRequest(
        @NotNull
        @Pattern(regexp = IDENTIFIER_PATTERN)
        String importId,

        @NotNull
        @Size(min = 1, max = IMPORT_TURN_LIMIT)
        List<@NotNull @Valid ImportedTurn> turns) {}
