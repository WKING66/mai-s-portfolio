package dev.amai.portfolio.system.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 账号响应字段装配；登录态、CSRF、角色和昵称回退必须在业务层确定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AccountConvert {
    AccountConvert MAPPER = Mappers.getMapper(AccountConvert.class);

    SessionVo to(UserAccountDO account, boolean loggedIn, String csrfToken, List<String> roles);

    @Mapping(target = "avatarUrl", source = "avatar.resourceUrl")
    @Mapping(target = "avatarExpiresAt", source = "avatar.expiresAt")
    AccountProfileVo to(UserAccountDO account, String nickname, AssetResourceData avatar);
}
