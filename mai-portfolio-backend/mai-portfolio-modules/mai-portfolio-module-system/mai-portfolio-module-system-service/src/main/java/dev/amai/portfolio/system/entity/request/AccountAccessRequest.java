package dev.amai.portfolio.system.entity.request;

import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import jakarta.validation.constraints.AssertTrue;

/** 站长调整指定账号的访问能力；只改传入项，不能用空请求制造一次无意义操作。 */
public record AccountAccessRequest(
        AccountStatus status,

        AccountType type) {
    @AssertTrue(message = "请至少指定账号状态或账号类型")
    public boolean isSpecified() {
        return status != null || type != null;
    }
}
