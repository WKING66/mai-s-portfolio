package dev.amai.portfolio.system.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 账号响应字段装配；登录态、CSRF、角色和昵称回退必须在业务层确定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AccountConvert {
    AccountConvert MAPPER = Mappers.getMapper(AccountConvert.class);

    SessionVo to(UserAccountDO account, boolean loggedIn, String csrfToken, List<String> roles);

    /** Redis 身份快照是明确的白名单投影，避免把密码摘要随实体一并序列化。 */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "username", source = "username")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "status", source = "status")
    UserAccountDO toSession(UserAccountDO account);

    @Mapping(target = "avatarUrl", source = "avatar.resourceUrl")
    @Mapping(target = "avatarExpiresAt", source = "avatar.expiresAt")
    AccountProfileVo to(UserAccountDO account, String nickname, AssetResourceData avatar);
}
