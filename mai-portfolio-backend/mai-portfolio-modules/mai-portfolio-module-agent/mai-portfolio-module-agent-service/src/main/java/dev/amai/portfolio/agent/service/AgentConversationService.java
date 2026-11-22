package dev.amai.portfolio.agent.service;

import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiConsumer;

public interface AgentConversationService {
    boolean enabled();

    String create(String owner, boolean anonymous);

    String claimAnonymous(String visitor, String account);

    ConversationPage list(String owner, Long cursor);

    ConversationDetails details(Long id, String owner);

    ConversationDetails clear(Long id, String owner);

    AgentTurn submit(Long conversation, String owner, boolean anonymous, String requestId, String question);

    Receipt receipt(AgentTurn turn);

    AgentTurn ownedExecution(Long execution, String owner);

    AgentExecutionSnapshot snapshot(AgentTurn turn);

    AgentExecutionSnapshot stop(AgentTurn turn);

    void events(AgentTurn turn, BiConsumer<String, String> output, Runnable keepAlive);

    record Receipt(
            String requestId,

            String executionId,

            String status) {}

    record ConversationSummary(
            String conversationId,

            String title,

            OffsetDateTime createdAt,

            OffsetDateTime updatedAt) {}

    record ConversationPage(
            List<ConversationSummary> items,

            String nextCursor) {}

    record ConversationDetails(
            String conversationId,

            String title,

            OffsetDateTime createdAt,

            List<TurnView> turns) {}

    record TurnView(
            String requestId,

            String executionId,

            String question,

            String text,

            String status,

            String errorMes,

            Long inputTokens,

            Long outputTokens,

            Integer usageStatus) {}
}
