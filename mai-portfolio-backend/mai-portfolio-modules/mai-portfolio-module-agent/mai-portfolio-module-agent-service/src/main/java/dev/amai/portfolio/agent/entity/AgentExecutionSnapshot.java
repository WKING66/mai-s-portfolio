package dev.amai.portfolio.agent.entity;

import java.util.List;

public record AgentExecutionSnapshot(
        String requestId,

        String executionId,

        String status,

        String text,

        List<String> sources,

        ExecutionError error,

        Long inputTokens,

        Long outputTokens,

        Integer usageStatus) {

    public AgentExecutionSnapshot(String requestId, String executionId, String status,
            String text, List<String> sources, ExecutionError error) {
        this(requestId, executionId, status, text, sources, error, null, null, 3);
    }

    public record ExecutionError(
            String code,

            String message,

            String requestId) {}
}
