package dev.amai.portfolio.system.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.security.context.AccountContext.Account;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 账号到登录身份、响应的字段映射；角色和昵称回退由业务确定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AccountConvert {
    AccountConvert MAPPER = Mappers.getMapper(AccountConvert.class);

    Account to(UserAccountDO account, List<String> roles);

    SessionVo to(Account account, boolean loggedIn, String csrfToken);

    @Mapping(target = "avatarUrl", source = "avatar.resourceUrl")
    @Mapping(target = "avatarExpiresAt", source = "avatar.expiresAt")
    AccountProfileVo to(Account account, String nickname, AssetResourceData avatar);
}
