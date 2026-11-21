package dev.amai.portfolio.agent.entity;

import java.time.LocalDateTime;

/** 数字人受理所用的站点配置。 */
public record AgentSiteSettings(
        boolean agentEnabled,

        int globalWindowHours,

        int globalRequestLimit,

        int visitorDailyTurnLimit,

        int accountDailyTurnLimit,

        LocalDateTime updatedAt) {}
