package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.entity.request.AccountAvatarRequest;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;

/** 账号本人资料；头像只绑定资产，不重复文件接收或存储能力。 */
public interface AccountProfileService {
    AccountProfileVo current();

    AccountProfileVo update(AccountProfileRequest request);

    AccountProfileVo bindAvatar(AccountAvatarRequest request);

    /** 新密码与凭据版本原子更新，旧会话在后续访问时失效。 */
    void changePassword(AccountPasswordRequest request);
}
