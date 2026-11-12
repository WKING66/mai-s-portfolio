package dev.amai.portfolio.agent.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 网站受理问答时使用的轮次额度。 */
@Validated
@ConfigurationProperties(prefix = "site-guide.quota")
public record AgentQuotaProperties(
        @Positive
        int globalWindowHours,

        @Positive
        int globalRequestLimit,

        @Positive
        int visitorDailyTurnLimit,

        @Positive
        int accountDailyTurnLimit,

        @DefaultValue("8")
        @Positive
        int chatBurstCapacity,

        @DefaultValue("4")
        @Positive
        int chatRefillPerSecond) {}
