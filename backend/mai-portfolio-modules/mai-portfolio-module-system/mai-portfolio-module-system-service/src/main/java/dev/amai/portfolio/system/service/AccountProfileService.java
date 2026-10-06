package dev.amai.portfolio.system.service;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import org.springframework.web.multipart.MultipartFile;

/** 普通账号和站长共用的本人资料能力；身份始终从现有会话获得。 */
public interface AccountProfileService {
    AccountProfileVo current();

    AccountProfileVo update(AccountProfileRequest request);

    AccountProfileVo uploadAvatar(MultipartFile file);

    AssetContentData openAvatar();

    /** 更新密码事务提交后撤销该账号全部会话；调用方必须重新登录。 */
    void changePassword(AccountPasswordRequest request);
}
