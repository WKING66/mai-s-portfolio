package dev.amai.portfolio.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.agent.config.AgentModelBudgetProperties;
import dev.amai.portfolio.agent.entity.AgentModelAttempt;
import dev.amai.portfolio.agent.entity.request.ModelBudgetReservation;
import dev.amai.portfolio.agent.entity.request.ModelBudgetSettlement;
import dev.amai.portfolio.agent.mapper.AgentModelBudgetMapper;
import dev.amai.portfolio.agent.mapper.AgentModelBudgetMapper.BudgetTotals;
import dev.amai.portfolio.web.exception.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentModelBudgetServiceTest {
    private static final String ATTEMPT_ID = "00000000-0000-0000-0000-000000000001";

    private final AgentModelBudgetMapper agentModelBudgetMapper = mock(AgentModelBudgetMapper.class);

    private final AgentModelBudgetService agentModelBudgetService = new AgentModelBudgetService(
            agentModelBudgetMapper, new AgentModelBudgetProperties(BigDecimal.ONE, false, "CNY"));

    @Test
    void refusesWhenLegacyAndCurrentChargesConsumeTheBudget() {
        when(agentModelBudgetMapper.totals(any(), eq("CNY")))
                .thenReturn(new BudgetTotals(new BigDecimal("0.20"), false));

        assertThrows(ApiException.class, () -> agentModelBudgetService.reserve(request("0.30")));

        verify(agentModelBudgetMapper, never()).insert(any(AgentModelAttempt.class));
    }

    @Test
    void recordsTheMaximumChargeBeforeAllowingTheCall() {
        when(agentModelBudgetMapper.totals(any(), eq("CNY")))
                .thenReturn(new BudgetTotals(new BigDecimal("0.20"), false));

        agentModelBudgetService.reserve(request("0.20"));

        var captured = ArgumentCaptor.forClass(AgentModelAttempt.class);
        verify(agentModelBudgetMapper).insert(captured.capture());
        assertEquals(new BigDecimal("0.600000"), captured.getValue().getCeiling());
        assertEquals(ATTEMPT_ID, captured.getValue().getId());
    }

    @Test
    void blocksDuplicateAttemptEvenWithRemainingBudget() {
        when(agentModelBudgetMapper.totals(any(), eq("CNY")))
                .thenReturn(new BudgetTotals(BigDecimal.ZERO, false));
        when(agentModelBudgetMapper.selectById(ATTEMPT_ID)).thenReturn(new AgentModelAttempt());

        assertThrows(ApiException.class, () -> agentModelBudgetService.reserve(request("0")));

        verify(agentModelBudgetMapper, never()).insert(any(AgentModelAttempt.class));
    }

    @Test
    void refusesAReservationThatArrivesAfterItsRelease() {
        when(agentModelBudgetMapper.totals(any(), eq("CNY")))
                .thenReturn(new BudgetTotals(BigDecimal.ZERO, false));
        when(agentModelBudgetMapper.wasCancelled(ATTEMPT_ID)).thenReturn(true);

        agentModelBudgetService.release(ATTEMPT_ID);

        verify(agentModelBudgetMapper).markCancelled(ATTEMPT_ID);
        assertThrows(ApiException.class, () -> agentModelBudgetService.reserve(request("0")));
        verify(agentModelBudgetMapper, never()).insert(any(AgentModelAttempt.class));
    }

    @Test
    void releasesAnUncalledAttemptAndPreservesSettledCharges() {
        var pending = new AgentModelAttempt();
        var settled = new AgentModelAttempt();
        settled.setActual(BigDecimal.ONE);
        when(agentModelBudgetMapper.selectById(ATTEMPT_ID)).thenReturn(pending, settled);

        agentModelBudgetService.release(ATTEMPT_ID);
        agentModelBudgetService.release(ATTEMPT_ID);

        verify(agentModelBudgetMapper).markCancelled(ATTEMPT_ID);
        verify(agentModelBudgetMapper).deleteById(ATTEMPT_ID);
    }

    @Test
    void retainsReservationAndFlagsProviderUsageAboveTheBound() {
        var attempt = new AgentModelAttempt();
        attempt.setInputLimit(100);
        attempt.setOutputLimit(100);
        when(agentModelBudgetMapper.selectById(ATTEMPT_ID)).thenReturn(attempt);

        assertFalse(agentModelBudgetService.settle(new ModelBudgetSettlement(ATTEMPT_ID, 101, 50)));

        assertEquals(null, attempt.getActual());
        assertEquals(true, attempt.isBoundsExceeded());
        verify(agentModelBudgetMapper).updateById(attempt);
    }

    private static ModelBudgetReservation request(String legacyAmount) {
        var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        return new ModelBudgetReservation(ATTEMPT_ID, "fixture", "CNY",
                new BigDecimal("4000"), new BigDecimal("2000"), 100, 100,
                new ModelBudgetReservation.LegacyUsage(day, new BigDecimal(legacyAmount), false));
    }
}
