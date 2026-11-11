package dev.amai.portfolio.agent.service;

import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

public interface AgentConversationService {
    boolean enabled();

    UUID create(String owner, boolean anonymous);

    ConversationPage list(String owner, UUID cursor);

    ConversationDetails details(UUID id, String owner);

    UUID importConversation(String owner, UUID importId, List<ImportedTurn> turns);

    AgentTurn submit(UUID conversation, String owner, UUID requestId, String question);

    Receipt receipt(AgentTurn turn);

    AgentTurn ownedExecution(UUID execution, String owner);

    AgentExecutionSnapshot snapshot(AgentTurn turn);

    AgentExecutionSnapshot stop(AgentTurn turn);

    void events(AgentTurn turn, BiConsumer<String, String> output);

    record Receipt(
            UUID requestId,

            UUID executionId,

            String status) {}

    record ConversationSummary(
            UUID conversationId,

            OffsetDateTime updatedAt) {}

    record ConversationPage(
            List<ConversationSummary> items,

            UUID nextCursor) {}

    record ConversationDetails(
            UUID conversationId,

            List<TurnView> turns) {}

    record TurnView(
            UUID requestId,

            UUID executionId,

            String question,

            String text,

            String status) {}

    record ImportedTurn(
            @NotBlank
            @Size(max = 4000)
            String question,

            @NotNull
            @Size(max = 4000)
            String answer) {}
}
