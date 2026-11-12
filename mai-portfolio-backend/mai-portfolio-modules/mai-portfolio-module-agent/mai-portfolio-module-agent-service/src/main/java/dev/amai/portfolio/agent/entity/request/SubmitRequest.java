package dev.amai.portfolio.agent.entity.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 提交一轮问答的请求。 */
public record SubmitRequest(
        @NotNull
        UUID requestId,

        @NotBlank
        @Size(max = 4000)
        String question) {}
