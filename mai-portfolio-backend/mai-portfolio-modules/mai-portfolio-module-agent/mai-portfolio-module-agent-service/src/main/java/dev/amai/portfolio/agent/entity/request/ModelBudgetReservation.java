package dev.amai.portfolio.agent.entity.request;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IDENTIFIER_PATTERN;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ModelBudgetReservation(
        @NotNull
        @Pattern(regexp = IDENTIFIER_PATTERN)
        String attemptId,

        @NotBlank
        String priceVersion,

        @NotNull
        @Pattern(regexp = "[A-Z]{3}")
        String currency,

        @NotNull
        @Positive
        BigDecimal inputPrice,

        @NotNull
        @PositiveOrZero
        BigDecimal outputPrice,

        @Positive
        int inputLimit,

        @PositiveOrZero
        int outputLimit,

        @Valid
        @NotNull
        LegacyUsage legacyUsage) {

    /** 旧 Python 账本只读占用额，用于跨版本继续计入已经发生或尚未确认的支出。 */
    public record LegacyUsage(
            @NotNull
            LocalDate billingDay,

            @NotNull
            @PositiveOrZero
            BigDecimal occupied,

            boolean blocked) {}
}
