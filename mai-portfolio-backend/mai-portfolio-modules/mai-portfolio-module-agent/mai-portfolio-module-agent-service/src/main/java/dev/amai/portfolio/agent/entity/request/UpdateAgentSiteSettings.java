package dev.amai.portfolio.agent.entity.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

public record UpdateAgentSiteSettings(
        @NotNull
        Boolean agentEnabled,

        @Positive
        int globalWindowHours,

        @Positive
        int globalRequestLimit,

        @Positive
        int visitorDailyTurnLimit,

        @Positive
        int accountDailyTurnLimit,

        @NotNull
        LocalDateTime expectedUpdatedAt) {}
