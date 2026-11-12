package dev.amai.portfolio.agent.entity;

import java.util.List;

public record AgentExecutionSnapshot(
        String requestId,

        String executionId,

        String status,

        String text,

        List<String> sources,

        ExecutionError error) {

    public record ExecutionError(
            String code,

            String message,

            String requestId) {}
}
