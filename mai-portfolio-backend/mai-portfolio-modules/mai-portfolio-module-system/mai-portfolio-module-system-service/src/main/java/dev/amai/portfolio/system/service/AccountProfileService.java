package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.entity.request.AccountAccessRequest;
import dev.amai.portfolio.system.entity.request.AccountAvatarRequest;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;

/** 账号资料与访问能力维护；头像只绑定资产，不重复文件接收或存储能力。 */
public interface AccountProfileService {
    AccountProfileVo current();

    AccountProfileVo update(AccountProfileRequest request);

    AccountProfileVo bindAvatar(AccountAvatarRequest request);

    /** 改密成功提交后注销账号全部登录；旧密码错误或数据库回滚不会注销。 */
    void changePassword(AccountPasswordRequest request);

    /** 站长停用账号或改变账号类型后，提交成功即注销该账号全部会话。 */
    void updateAccess(long accountId, AccountAccessRequest request);
}
