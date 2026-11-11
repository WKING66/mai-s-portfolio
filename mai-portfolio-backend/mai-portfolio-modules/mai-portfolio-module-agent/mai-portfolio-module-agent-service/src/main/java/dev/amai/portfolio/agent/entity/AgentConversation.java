package dev.amai.portfolio.agent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentConversation(
        UUID id,

        String ownerKey,

        boolean anonymous,

        OffsetDateTime updatedAt) {}
