package dev.amai.portfolio.agent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentTurn(
        UUID requestId,

        UUID conversationId,

        UUID executionId,

        String question,

        String answer,

        short status,

        String errorCode,

        OffsetDateTime acceptBefore) {}
