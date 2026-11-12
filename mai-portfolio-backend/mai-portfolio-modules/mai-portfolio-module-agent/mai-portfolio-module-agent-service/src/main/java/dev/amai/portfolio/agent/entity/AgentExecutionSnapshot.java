package dev.amai.portfolio.agent.entity;

import java.util.List;
import java.util.UUID;

public record AgentExecutionSnapshot(
        UUID requestId,

        UUID executionId,

        String status,

        String text,

        List<String> sources,

        ExecutionError error) {

    public record ExecutionError(
            String code,

            String message,

            UUID requestId) {}
}
