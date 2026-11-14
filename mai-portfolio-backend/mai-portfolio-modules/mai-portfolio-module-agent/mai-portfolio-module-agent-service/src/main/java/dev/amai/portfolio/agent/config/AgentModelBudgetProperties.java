package dev.amai.portfolio.agent.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Java 负责校验模型调用的全站金额上限。 */
@Validated
@ConfigurationProperties("site-guide.model-budget")
public record AgentModelBudgetProperties(
        @NotNull
        @DecimalMin("0")
        BigDecimal dailyLimit,

        boolean unlimited,

        @NotNull
        @Pattern(regexp = "[A-Z]{3}")
        String currency) {}
