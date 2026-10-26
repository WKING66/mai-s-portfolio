package dev.amai.portfolio.blog.entity.request;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.REQUEST_ID_PATTERN;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 只保存 Markdown 及其资源引用；文章属性不是本请求的输入，旧 slug 不影响正文保存。 */
public record BlogContentRequest(
        @NotNull
        @Positive
        Long revision,

        @NotBlank
        @Pattern(regexp = REQUEST_ID_PATTERN)
        String requestId,

        @NotNull
        String content,

        boolean remoteRightsConfirmed) {}
