package dev.amai.portfolio.agent.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AgentConversationRecoveryTest {
    private final AgentConversationMapper agentConversationMapper = mock(AgentConversationMapper.class);
    private final AgentAiClient agentAiClient = mock(AgentAiClient.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentConversationServiceImpl agentConversationService = new AgentConversationServiceImpl(
            agentConversationMapper, agentAiClient, transactionTemplate, true);

    @Test
    void missingExecutionDoesNotBlockCompletedAndExpiredTurns() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0)
                        .doInTransaction(new SimpleTransactionStatus()));

        var missing = boundTurn();
        var completed = boundTurn();
        var expired = boundTurn();
        when(agentConversationMapper.unfinished(12)).thenReturn(List.of(missing, completed, expired));
        when(agentConversationMapper.byRequest(missing.requestId())).thenReturn(missing);
        when(agentConversationMapper.byRequest(completed.requestId())).thenReturn(completed);
        when(agentConversationMapper.byRequest(expired.requestId())).thenReturn(expired);
        when(agentAiClient.findExecution(completed.executionId())).thenReturn(new AgentExecutionSnapshot(
                completed.requestId(), completed.executionId(), "completed", "回答", List.of(), null));
        when(agentAiClient.findExecution(expired.executionId())).thenReturn(new AgentExecutionSnapshot(
                expired.requestId(), expired.executionId(), "completed", "", List.of(),
                new AgentExecutionSnapshot.ExecutionError("EXECUTION_TEXT_EXPIRED", "正文已过期", expired.requestId())));

        agentConversationService.recoverUnfinished();

        verify(agentConversationMapper).saveTerminal(
                missing.requestId(), missing.executionId(), "", (short) 4, "EXECUTION_NOT_FOUND");
        verify(agentConversationMapper).saveTerminal(
                completed.requestId(), completed.executionId(), "回答", (short) 3, null);
        verify(agentConversationMapper).saveTerminal(
                expired.requestId(), expired.executionId(), "", (short) 4, "EXECUTION_TEXT_EXPIRED");
    }

    @Test
    void lookupStartedBeforeDeadlineCannotFailTurnEvenIfLookupIsSlow() {
        var deadline = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
        var pending = new AgentTurn(UUID.randomUUID(), UUID.randomUUID(), null, "问题", "", (short) 0, null,
                deadline);
        when(agentConversationMapper.unfinished(12)).thenReturn(List.of(pending));
        when(agentAiClient.byRequest(pending.requestId())).thenAnswer(invocation -> {
            while (OffsetDateTime.now(ZoneOffset.UTC).isBefore(deadline)) {
                Thread.sleep(10);
            }
            return null;
        });

        agentConversationService.recoverUnfinished();

        verify(agentAiClient).byRequest(pending.requestId());
        verify(agentConversationMapper, never()).failUnbound(
                pending.requestId(), (short) 4, "ACCEPTANCE_EXPIRED");
    }

    private static AgentTurn boundTurn() {
        return new AgentTurn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "问题", "", (short) 1, null,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }
}
