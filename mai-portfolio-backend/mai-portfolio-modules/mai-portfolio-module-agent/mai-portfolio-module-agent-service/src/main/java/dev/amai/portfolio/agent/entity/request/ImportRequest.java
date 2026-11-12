package dev.amai.portfolio.agent.entity.request;

import dev.amai.portfolio.agent.service.AgentConversationService.ImportedTurn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 导入已有问答历史的请求。 */
public record ImportRequest(
        @NotNull
        UUID importId,

        @NotNull
        @Size(min = 1, max = 20)
        List<@NotNull @Valid ImportedTurn> turns) {}
