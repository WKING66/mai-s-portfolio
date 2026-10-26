package dev.amai.portfolio.portfolio.service;

import dev.amai.portfolio.portfolio.entity.request.UpdateProfileRequest;
import dev.amai.portfolio.portfolio.entity.vo.SiteProfileVo;

/** 网站公开资料，与当前登录账号资料是不同业务事实。 */
public interface SiteProfileService {
    /** 网站介绍及可用资源链接；不是当前登录账号资料。 */
    SiteProfileVo profile();

    /** 仅替换公开文本字段，不隐式修改媒体和 SEO 配置。 */
    SiteProfileVo updateProfile(UpdateProfileRequest request);
}
