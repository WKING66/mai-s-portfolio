package dev.amai.portfolio.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.core.util.IdUtil;
import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.component.AgentGlobalQuota;
import dev.amai.portfolio.agent.entity.AgentConversation;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentSiteSettings;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.enums.AgentTurnState;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import dev.amai.portfolio.agent.mapper.AgentSiteSettingsMapper;
import dev.amai.portfolio.agent.mapper.AgentTurnMapper;
import dev.amai.portfolio.agent.service.AgentSiteSettingsService;
import dev.amai.portfolio.redis.rate.RedisRateLimiter;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class AgentConversationRecoveryTest {
    private static final OffsetDateTime ADMISSION_TIME = OffsetDateTime.parse("2026-11-12T16:01:00Z");
    private final AgentConversationMapper agentConversationMapper = mock(AgentConversationMapper.class);
    private final AgentTurnMapper agentTurnMapper = mock(AgentTurnMapper.class);
    private final AgentAiClient agentAiClient = mock(AgentAiClient.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final AgentSiteSettingsService agentSiteSettingsService = mock(AgentSiteSettingsService.class);
    private final RedisRateLimiter redisRateLimiter = mock(RedisRateLimiter.class);
    private final AgentConversationServiceImpl agentConversationService = new AgentConversationServiceImpl(
            agentConversationMapper, agentTurnMapper, agentAiClient, transactionTemplate, agentSiteSettingsService,
            new AgentGlobalQuota(agentTurnMapper, mock(AgentSiteSettingsMapper.class), redisRateLimiter));

    @BeforeEach
    void executeTransactions() {
        when(agentSiteSettingsService.read()).thenReturn(
                new AgentSiteSettings(true, 2, 3, 1, 2, LocalDateTime.of(2026, 1, 1, 0, 0)));
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0)
                        .doInTransaction(new SimpleTransactionStatus()));
    }

    @Test
    void missingExecutionDoesNotBlockCompletedAndExpiredTurns() {
        var missing = boundTurn(1L);
        var completed = boundTurn(2L);
        var expired = boundTurn(3L);
        when(agentTurnMapper.unfinished(12)).thenReturn(List.of(missing, completed, expired));
        when(agentTurnMapper.byRequestId(missing.getRequestId())).thenReturn(missing);
        when(agentTurnMapper.byRequestId(completed.getRequestId())).thenReturn(completed);
        when(agentTurnMapper.byRequestId(expired.getRequestId())).thenReturn(expired);
        when(agentAiClient.findExecution(completed.getExecutionId().toString())).thenReturn(new AgentExecutionSnapshot(
                completed.getRequestId(), completed.getExecutionId().toString(), "completed", "回答", List.of(), null));
        when(agentAiClient.findExecution(expired.getExecutionId().toString())).thenReturn(new AgentExecutionSnapshot(
                expired.getRequestId(), expired.getExecutionId().toString(), "completed", "", List.of(),
                new AgentExecutionSnapshot.ExecutionError("EXECUTION_TEXT_EXPIRED", "正文已过期", expired.getRequestId())));

        agentConversationService.recoverUnfinished();

        verify(agentTurnMapper).saveTerminal(
                missing.getRequestId(), missing.getExecutionId(), "", AgentTurnState.FAILED,
                "模型服务暂不可用。", null, null, 3);
        verify(agentTurnMapper).saveTerminal(
                completed.getRequestId(), completed.getExecutionId(), "回答", AgentTurnState.COMPLETED,
                null, null, null, 3);
        verify(agentTurnMapper).saveTerminal(
                expired.getRequestId(), expired.getExecutionId(), "", AgentTurnState.FAILED,
                "原回答已无法恢复。", null, null, 3);
    }

    @Test
    void lookupStartedBeforeDeadlineCannotFailTurnEvenIfLookupIsSlow() {
        var deadline = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
        var pending = new AgentTurn(IdUtil.randomUUID(), 1L, null, "问题", "", AgentTurnState.PENDING, null,
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
                pending.getRequestId(), "这次发送未受理，请重新发送。");
    }

    @Test
    void existingRequestCanResumeWithoutConsumingQuota() {
        var stored = boundTurn(1L);
        String owner = "account:7";
        givenOwnedConversation(stored.getConversationId(), owner);
        when(agentTurnMapper.byRequestId(stored.getRequestId())).thenReturn(stored);

        assertSame(stored, agentConversationService.submit(
                stored.getConversationId(), owner, false, stored.getRequestId(), stored.getQuestion()));

        verify(agentTurnMapper, never()).countOwnerQuotaSince(anyString(), any());
        verify(redisRateLimiter, never()).acquireSlidingWindow(anyString(), anyString(), any(), anyInt());
        verify(agentTurnMapper, never()).insertPending(any());
    }

    @Test
    void exhaustedGlobalQuotaRejectsBeforeWritingTheTurn() {
        givenOwnedConversation(1L, "visitor:7");
        when(redisRateLimiter.acquireSlidingWindow("site-guide:chat", "request", Duration.ofHours(2), 3))
                .thenReturn(RedisRateLimiter.SlidingWindowResult.EXHAUSTED);

        var error = assertThrows(ApiException.class, () -> agentConversationService.submit(
                1L, "visitor:7", true, "request", "问题"));

        assertEquals("站点对话额度暂已用完，请稍后再试。", error.getMessage());
        var order = inOrder(agentTurnMapper, redisRateLimiter);
        order.verify(agentTurnMapper).lockOwner("visitor:7");
        order.verify(agentTurnMapper).countOwnerQuotaSince("visitor:7", ADMISSION_TIME.minusHours(24));
        order.verify(redisRateLimiter).acquireSlidingWindow("site-guide:chat", "request", Duration.ofHours(2), 3);
        verify(agentTurnMapper, never()).insertPending(any());
        verify(agentAiClient, never()).submit(any());
    }

    @Test
    void redisOutageFailsClosedBeforeWritingTheTurn() {
        givenOwnedConversation(1L, "account:7");
        when(redisRateLimiter.acquireSlidingWindow("site-guide:chat", "request", Duration.ofHours(2), 3))
                .thenThrow(new RedisException("Redis connection unavailable"));

        var error = assertThrows(ApiException.class, () -> agentConversationService.submit(
                1L, "account:7", false, "request", "问题"));

        assertEquals("站点对话暂不可用，请稍后重试。", error.getMessage());
        verify(agentTurnMapper, never()).insertPending(any());
        verify(agentAiClient, never()).submit(any());
    }

    @Test
    void emptyRedisAfterRestartDoesNotAllowNewTurns() {
        givenOwnedConversation(1L, "account:7");
        when(redisRateLimiter.acquireSlidingWindow("site-guide:chat", "request", Duration.ofHours(2), 3))
                .thenReturn(RedisRateLimiter.SlidingWindowResult.UNINITIALIZED);

        assertThrows(ApiException.class, () -> agentConversationService.submit(
                1L, "account:7", false, "request", "问题"));

        verify(agentTurnMapper, never()).quotaHistorySince(any());
        verify(agentTurnMapper, never()).insertPending(any());
    }

    @Test
    void visitorAndAccountUseIndependentLimitsAcrossMidnight() {
        when(agentTurnMapper.countOwnerQuotaSince("visitor:7", ADMISSION_TIME.minusHours(24))).thenReturn(1L);
        when(agentTurnMapper.countOwnerQuotaSince("account:7", ADMISSION_TIME.minusHours(24))).thenReturn(2L);
        for (String owner : List.of("visitor:7", "account:7")) {
            givenOwnedConversation(1L, owner);
            var error = assertThrows(ApiException.class, () -> agentConversationService.submit(
                    1L, owner, owner.startsWith("visitor:"), "request", "问题"));
            assertEquals("你的对话额度暂已用完，请稍后再试。", error.getMessage());
        }

        verify(redisRateLimiter, never()).acquireSlidingWindow(anyString(), anyString(), any(), anyInt());
        verify(agentTurnMapper, never()).insertPending(any());
    }

    @Test
    void claimLocksVisitorThenAccountBeforeChangingOwnership() {
        when(agentConversationMapper.findAnonymous("visitor:7")).thenReturn(1L);
        when(agentConversationMapper.claimAnonymous(1L, "visitor:7", "account:7")).thenReturn(1);

        assertEquals("1", agentConversationService.claimAnonymous("visitor:7", "account:7"));

        var order = inOrder(agentTurnMapper, agentConversationMapper);
        order.verify(agentTurnMapper).lockOwner("visitor:7");
        order.verify(agentConversationMapper).findAnonymous("visitor:7");
        order.verify(agentTurnMapper).lockOwner("account:7");
        order.verify(agentConversationMapper).claimAnonymous(1L, "visitor:7", "account:7");
    }

    @Test
    void publicProgressDiscardsProviderDetailsAndUnknownPhases() {
        var client = new AgentAiClient("http://localhost", "", JsonMapper.builder().build());

        assertEquals("{\"phase\":\"thinking\"}", client.publicProgress(
                "{\"phase\":\"thinking\",\"reasoning_content\":\"private fixture\",\"model\":\"fixture\"}"));
        assertNull(client.publicProgress("{\"phase\":\"unknown\"}"));
    }

    private void givenOwnedConversation(Long id, String owner) {
        when(agentTurnMapper.lockOwner(owner)).thenReturn(ADMISSION_TIME);
        when(agentConversationMapper.findOwned(id, owner)).thenReturn(new AgentConversation());
    }

    private static AgentTurn boundTurn(long executionId) {
        return new AgentTurn(IdUtil.randomUUID(), 1L, executionId, "问题", "", AgentTurnState.ACCEPTED, null,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }
}
