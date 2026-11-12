package dev.amai.portfolio.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.core.util.IdUtil;
import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.config.AgentQuotaProperties;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.enums.AgentTurnState;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import dev.amai.portfolio.agent.mapper.AgentTurnMapper;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
            agentConversationMapper, agentTurnMapper, agentAiClient, transactionTemplate, new AgentQuotaProperties(2, 3, 1, 2), true);

    @BeforeEach
    void executeTransactions() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0)
                        .doInTransaction(new SimpleTransactionStatus()));
    }

    @Test
    void missingExecutionDoesNotBlockCompletedAndExpiredTurns() {
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

    @Test
    void existingRequestCanResumeWithoutConsumingQuota() {
        var stored = boundTurn();
        String owner = "account:7";
        when(agentConversationMapper.lockConversation(stored.getConversationId(), owner))
                .thenReturn(stored.getConversationId());
        when(agentTurnMapper.selectById(stored.getRequestId())).thenReturn(stored);

        assertSame(stored, agentConversationService.submit(
                stored.getConversationId(), owner, false, stored.getRequestId(), stored.getQuestion()));

        verify(agentTurnMapper, never()).lockQuota(anyLong());
        verify(agentTurnMapper, never()).insertPending(any());
    }

    @Test
    void globalQuotaRejectsBothGuestAndAccountBeforePersonalQuota() {
        var now = OffsetDateTime.parse("2026-11-12T16:01:00Z");
        when(agentTurnMapper.lockQuota(anyLong())).thenReturn(now);
        when(agentTurnMapper.countQuotaSince(now.minusHours(2))).thenReturn(3L);
        for (String owner : List.of("visitor:7", "account:7")) {
            when(agentConversationMapper.lockConversation("conversation", owner)).thenReturn("conversation");
            var error = assertThrows(ApiException.class, () -> agentConversationService.submit(
                    "conversation", owner, owner.startsWith("visitor:"), "request", "问题"));
            assertEquals("站点对话额度暂已用完，请稍后再试。", error.getMessage());
        }

        verify(agentTurnMapper, never()).countOwnerQuotaSince(anyString(), any());
        verify(agentTurnMapper, never()).insertPending(any());
        verify(agentAiClient, never()).submit(any());
    }

    @Test
    void visitorAndAccountUseIndependentLimitsAcrossMidnight() {
        var now = OffsetDateTime.parse("2026-11-12T16:01:00Z");
        when(agentTurnMapper.lockQuota(anyLong())).thenReturn(now);
        when(agentTurnMapper.countOwnerQuotaSince("visitor:7", now.minusHours(24))).thenReturn(1L);
        when(agentTurnMapper.countOwnerQuotaSince("account:7", now.minusHours(24))).thenReturn(2L);
        for (String owner : List.of("visitor:7", "account:7")) {
            when(agentConversationMapper.lockConversation("conversation", owner)).thenReturn("conversation");
            var error = assertThrows(ApiException.class, () -> agentConversationService.submit(
                    "conversation", owner, owner.startsWith("visitor:"), "request", "问题"));
            assertEquals("你的对话额度暂已用完，请稍后再试。", error.getMessage());
        }

        verify(agentTurnMapper).countOwnerQuotaSince("visitor:7", now.minusHours(24));
        verify(agentTurnMapper).countOwnerQuotaSince("account:7", now.minusHours(24));
        verify(agentTurnMapper, never()).insertPending(any());
    }

    private static AgentTurn boundTurn() {
        return new AgentTurn(IdUtil.randomUUID(), IdUtil.randomUUID(), IdUtil.randomUUID(), "问题", "", AgentTurnState.ACCEPTED, null,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }
}
