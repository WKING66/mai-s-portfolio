package dev.amai.portfolio.agent.entity.request;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IDENTIFIER_PATTERN;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record ModelBudgetSettlement(
        @NotNull
        @Pattern(regexp = IDENTIFIER_PATTERN)
        String attemptId,

        @PositiveOrZero
        int inputTokens,

        @PositiveOrZero
        int outputTokens) {}
