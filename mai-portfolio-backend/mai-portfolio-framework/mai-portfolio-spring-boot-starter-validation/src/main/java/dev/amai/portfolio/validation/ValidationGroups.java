package dev.amai.portfolio.validation;

import jakarta.validation.groups.Default;

/** 按请求动作选择约束；更新组继承默认组，避免启用版本校验时漏掉普通字段约束。 */
public final class ValidationGroups {
    private ValidationGroups() {}

    public interface Update extends Default {}
}
