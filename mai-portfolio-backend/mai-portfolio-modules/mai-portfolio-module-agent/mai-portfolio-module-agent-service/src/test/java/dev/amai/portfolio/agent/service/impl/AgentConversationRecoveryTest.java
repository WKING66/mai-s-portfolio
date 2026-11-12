package dev.amai.portfolio.agent.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.core.util.IdUtil;
import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.enums.AgentTurnState;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import dev.amai.portfolio.agent.mapper.AgentTurnMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AgentConversationRecoveryTest {
    private final AgentConversationMapper agentConversationMapper = mock(AgentConversationMapper.class);
    private final AgentTurnMapper agentTurnMapper = mock(AgentTurnMapper.class);
    private final AgentAiClient agentAiClient = mock(AgentAiClient.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentConversationServiceImpl agentConversationService = new AgentConversationServiceImpl(
            agentConversationMapper, agentTurnMapper, agentAiClient, transactionTemplate, true);

    @Test
    void missingExecutionDoesNotBlockCompletedAndExpiredTurns() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0)
                        .doInTransaction(new SimpleTransactionStatus()));

        var missing = boundTurn();
        var completed = boundTurn();
        var expired = boundTurn();
        when(agentTurnMapper.unfinished(12)).thenReturn(List.of(missing, completed, expired));
        when(agentTurnMapper.selectById(missing.getRequestId())).thenReturn(missing);
        when(agentTurnMapper.selectById(completed.getRequestId())).thenReturn(completed);
        when(agentTurnMapper.selectById(expired.getRequestId())).thenReturn(expired);
        when(agentAiClient.findExecution(completed.getExecutionId())).thenReturn(new AgentExecutionSnapshot(
                completed.getRequestId(), completed.getExecutionId(), "completed", "回答", List.of(), null));
        when(agentAiClient.findExecution(expired.getExecutionId())).thenReturn(new AgentExecutionSnapshot(
                expired.getRequestId(), expired.getExecutionId(), "completed", "", List.of(),
                new AgentExecutionSnapshot.ExecutionError("EXECUTION_TEXT_EXPIRED", "正文已过期", expired.getRequestId())));

        agentConversationService.recoverUnfinished();

        verify(agentTurnMapper).saveTerminal(
                missing.getRequestId(), missing.getExecutionId(), "", AgentTurnState.FAILED, "EXECUTION_NOT_FOUND");
        verify(agentTurnMapper).saveTerminal(
                completed.getRequestId(), completed.getExecutionId(), "回答", AgentTurnState.COMPLETED, null);
        verify(agentTurnMapper).saveTerminal(
                expired.getRequestId(), expired.getExecutionId(), "", AgentTurnState.FAILED, "EXECUTION_TEXT_EXPIRED");
    }

    @Test
    void lookupStartedBeforeDeadlineCannotFailTurnEvenIfLookupIsSlow() {
        var deadline = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
        var pending = new AgentTurn(IdUtil.randomUUID(), IdUtil.randomUUID(), null, "问题", "", AgentTurnState.PENDING, null,
                deadline);
        when(agentTurnMapper.unfinished(12)).thenReturn(List.of(pending));
        when(agentAiClient.byRequest(pending.getRequestId())).thenAnswer(invocation -> {
            while (OffsetDateTime.now(ZoneOffset.UTC).isBefore(deadline)) {
                Thread.sleep(10);
            }
            return null;
        });

        agentConversationService.recoverUnfinished();

        verify(agentAiClient).byRequest(pending.getRequestId());
        verify(agentTurnMapper, never()).failUnbound(
                pending.getRequestId(), "ACCEPTANCE_EXPIRED");
    }

    private static AgentTurn boundTurn() {
        return new AgentTurn(IdUtil.randomUUID(), IdUtil.randomUUID(), IdUtil.randomUUID(), "问题", "", AgentTurnState.ACCEPTED, null,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }
}
