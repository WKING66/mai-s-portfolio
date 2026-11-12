package dev.amai.portfolio.agent.entity.request;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IDENTIFIER_PATTERN;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.MESSAGE_LENGTH_LIMIT;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 提交一轮问答的请求。 */
public record SubmitRequest(
        @NotNull
        @Pattern(regexp = IDENTIFIER_PATTERN)
        String requestId,

        @NotBlank
        @Size(max = MESSAGE_LENGTH_LIMIT)
        String question) {}
