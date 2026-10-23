package dev.amai.portfolio.blog.entity.request;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.REQUEST_ID_PATTERN;

import jakarta.validation.constraints.*;

/** 状态动作与删除共用写修订；同一请求标识只允许重试相同动作。 */
public record BlogRevisionRequest(
        @NotNull
        @Positive
        Long revision,

        @NotBlank
        @Pattern(regexp = REQUEST_ID_PATTERN)
        String requestId) {}
