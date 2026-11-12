package dev.amai.portfolio.agent.service;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.MESSAGE_LENGTH_LIMIT;

import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiConsumer;

public interface AgentConversationService {
    boolean enabled();

    String create(String owner, boolean anonymous);

    ConversationPage list(String owner, String cursor);

    ConversationDetails details(String id, String owner);

    String importConversation(String owner, String importId, List<ImportedTurn> turns);

    AgentTurn submit(String conversation, String owner, String requestId, String question);

    Receipt receipt(AgentTurn turn);

    AgentTurn ownedExecution(String execution, String owner);

    AgentExecutionSnapshot snapshot(AgentTurn turn);

    AgentExecutionSnapshot stop(AgentTurn turn);

    void events(AgentTurn turn, BiConsumer<String, String> output);

    record Receipt(
            String requestId,

            String executionId,

            String status) {}

    record ConversationSummary(
            String conversationId,

            OffsetDateTime updatedAt) {}

    record ConversationPage(
            List<ConversationSummary> items,

            String nextCursor) {}

    record ConversationDetails(
            String conversationId,

            List<TurnView> turns) {}

    record TurnView(
            String requestId,

            String executionId,

            String question,

            String text,

            String status) {}

    record ImportedTurn(
            @NotBlank
            @Size(max = MESSAGE_LENGTH_LIMIT)
            String question,

            @NotNull
            @Size(max = MESSAGE_LENGTH_LIMIT)
            String answer) {}
}
