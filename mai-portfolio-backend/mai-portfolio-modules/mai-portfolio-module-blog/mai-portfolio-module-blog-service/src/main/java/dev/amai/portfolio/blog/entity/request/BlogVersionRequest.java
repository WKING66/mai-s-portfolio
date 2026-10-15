package dev.amai.portfolio.blog.entity.request;

import jakarta.validation.constraints.*;

/** 发布/下架携带当前文档锁版本，过期输入交由统一 DATA_CONFLICT 处理。 */
public record BlogVersionRequest(
        @NotNull
        @Positive
        Long version) {}
