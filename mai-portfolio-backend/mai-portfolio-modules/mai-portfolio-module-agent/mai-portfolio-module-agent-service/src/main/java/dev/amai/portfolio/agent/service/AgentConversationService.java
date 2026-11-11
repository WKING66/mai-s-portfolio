package dev.amai.portfolio.agent.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AgentConversationService {
    boolean enabled();

    UUID create(String owner, boolean anonymous);

    ConversationPage list(String owner, UUID cursor);

    ConversationDetails details(UUID id, String owner);

    UUID importConversation(String owner, UUID importId, List<ImportedTurn> turns);

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
