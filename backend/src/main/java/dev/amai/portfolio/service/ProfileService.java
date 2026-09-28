package dev.amai.portfolio.service;

import dev.amai.portfolio.dto.PublicProfileResponse;
import dev.amai.portfolio.dto.AdminProfileResponse;
import dev.amai.portfolio.dto.UpdateProfileRequest;

public interface ProfileService {
    /** 只组装已获准公开的资料；媒体可用性不等于媒体下载授权。 */
    PublicProfileResponse publicProfile();

    /** 读取站长可编辑字段及并发更新所需的时间标记。 */
    AdminProfileResponse adminProfile();

    /** 只替换获准公开的文本字段，不隐式修改媒体和 SEO 配置。 */
    AdminProfileResponse updateProfile(UpdateProfileRequest request);
}
