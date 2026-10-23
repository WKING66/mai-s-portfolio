package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.security.SecurityMessageConstants;
import dev.amai.portfolio.security.context.AccountContext.Account;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.component.NewPasswordPolicy;
import dev.amai.portfolio.system.constant.AccountMessageConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.convert.AccountConvert;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.domain.UserProfileDO;
import dev.amai.portfolio.system.entity.request.AccountAccessRequest;
import dev.amai.portfolio.system.entity.request.AccountAvatarRequest;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.mapper.UserProfileMapper;
import dev.amai.portfolio.system.service.AccountProfileService;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 本人资料与站长的账号访问管理；本人操作从上下文取身份，访问管理的目标账号由路径指定。 */
@Service
public class AccountProfileServiceImpl implements AccountProfileService {
    private static final int MAX_NICKNAME_CHARACTERS = 64;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final AssetQueryService assetQueryService;
    private final PasswordCryptoService passwordCryptoService;
    private final PasswordHasher passwordHasher;
    private final NewPasswordPolicy newPasswordPolicy;

    public AccountProfileServiceImpl(
            UserAccountMapper userAccountMapper,
            UserProfileMapper userProfileMapper,
            AssetQueryService assetQueryService,
            PasswordCryptoService passwordCryptoService,
            PasswordHasher passwordHasher,
            NewPasswordPolicy newPasswordPolicy) {
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.assetQueryService = assetQueryService;
        this.passwordCryptoService = passwordCryptoService;
        this.passwordHasher = passwordHasher;
        this.newPasswordPolicy = newPasswordPolicy;
    }

    /** 读取当前登录账号的昵称和头像地址；与网站首页展示的站长介绍是两份不同资料。 */
    @Override
    public AccountProfileVo current() {
        var account = AccountContext.currentAccount();
        return view(account, findProfile(account.id()));
    }

    /** 只修改本人的昵称，保留已有头像；昵称未填写时使用账号用户名展示。 */
    @Override
    @Transactional
    public AccountProfileVo update(AccountProfileRequest request) {
        // 1. 规范化昵称，再锁定当前账号，串行化同账号的资料创建与修改。
        String nickname = nickname(request.nickname());
        var identity = AccountContext.currentAccount();
        UserAccountDO account = lockAccount(identity.id());
        UserProfileDO profile = profileForWrite(account.getId());
        // 2. 仅写本人昵称；头像等其他资料保持原样，响应通过资产查询生成。
        profile.setNickname(nickname);
        saveProfile(profile);
        return view(identity, profile);
    }

    /** 从本人上传的头像资产中选择新头像；这里只改变绑定，不能拿别人的文件，也不删除旧头像文件。 */
    @Override
    @Transactional
    public AccountProfileVo bindAvatar(AccountAvatarRequest request) {
        // 1. 锁定本人账号后校验资产引用，禁止以请求数据选择其他账号。
        var identity = AccountContext.currentAccount();
        UserAccountDO account = lockAccount(identity.id());
        // 2. 只允许本人上传、AVATAR 用途且 READY 的图片；复用 Asset，不重复上传。
        var asset = Optional.ofNullable(
                assetQueryService.lockReady(Set.of(request.assetId())).get(request.assetId()))
                        .filter(
                                resource ->
                                        resource.type() == AssetType.IMAGE
                                                && resource.usage() == AssetUsage.AVATAR
                                                && account.getId()
                                                        .equals(resource.uploaderAccountId()))
                        .orElseThrow(() -> invalid(AccountMessageConstants.AVATAR_BINDING_INVALID));
        UserProfileDO profile = profileForWrite(account.getId());
        // 3. 同事务更新绑定；不删除旧 OSS 对象，资源清理由资产生命周期负责。
        profile.setAvatarMediaId(asset.id());
        saveProfile(profile);
        return view(identity, profile);
    }

    /** 原密码正确才修改密码；输错或保存失败仍保留登录，成功后要求该账号在所有设备重新登录。 */
    @Override
    @Transactional
    public void changePassword(AccountPasswordRequest request) {
        // 1. 在任何持久化修改前检查事务，避免非代理调用误把自动提交写入当成安全改密。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(AccountMessageConstants.PASSWORD_TRANSACTION_REQUIRED);
        }
        // 2. 解密并校验密码，锁定账号后精确匹配旧密码；只规范化新密码。
        String oldPassword = passwordCryptoService.decryptPassword(request.oldEncryptedPassword());
        String newPassword =
                newPasswordPolicy.normalize(
                        passwordCryptoService.decryptPassword(request.newEncryptedPassword()));
        var identity = AccountContext.currentAccount();
        UserAccountDO account = lockAccount(identity.id());
        if (!passwordHasher.matches(oldPassword, account.getPasswordHash())) {
            // 422 保留当前登录态，不能把“旧密码输错”误当成会话失效的 401。
            throw invalid(AccountMessageConstants.CURRENT_PASSWORD_INVALID);
        }
        if (oldPassword.equals(newPassword)) {
            throw invalid(AccountMessageConstants.PASSWORD_UNCHANGED);
        }
        // 3. 在同一事务中条件更新密码和凭据版本；并发冲突不能被当作成功。
        int changed =
                userAccountMapper.update(
                        null,
                        Wrappers.<UserAccountDO>lambdaUpdate()
                                .eq(UserAccountDO::getId, account.getId())
                                .eq(UserAccountDO::getPasswordHash, account.getPasswordHash())
                                .eq(
                                        UserAccountDO::getCredentialVersion,
                                        account.getCredentialVersion())
                                .set(
                                        UserAccountDO::getCredentialVersion,
                                        Math.addExact(account.getCredentialVersion(), 1L))
                                .set(
                                        UserAccountDO::getPasswordHash,
                                        passwordHasher.encode(newPassword))
                                .set(
                                        UserAccountDO::getUpdatedAt,
                                        LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) {
            throw new ApiException(
                    ApiErrorCode.DATA_CONFLICT, AccountMessageConstants.ACCOUNT_WRITE_CONFLICT);
        }
        // 4. 只有数据库提交成功后才注销 Redis 会话；回滚不影响原登录。
        TransactionSynchronizationManager.registerSynchronization(
                new LogoutAfterCommit(account.getId()));
    }

    /**
     * 站长调整账号访问能力。被停用或改变类型的账号必须重新登录，
     * 不能继续沿用 Redis 中原来的可用状态或 OWNER 身份。
     */
    @Override
    @Transactional
    public void updateAccess(long accountId, AccountAccessRequest request) {
        // 1. 只选择路径中的目标账号；入口已经限制为 OWNER，Service 不重复查角色。
        var account = userAccountMapper.selectForUpdate(accountId);
        if (account == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "账号不存在");
        }
        boolean typeChanged = request.type() != null && account.getType() != request.type().code();
        boolean statusChanged = request.status() != null && account.getStatus() != request.status().code();
        // 2. 只修改明确传入且有变化的字段，不影响密码、昵称和头像。
        if (typeChanged || statusChanged) {
            userAccountMapper.update(null, Wrappers.<UserAccountDO>lambdaUpdate()
                    .eq(UserAccountDO::getId, accountId)
                    .set(request.type() != null, UserAccountDO::getType,
                            request.type() == null ? null : request.type().code())
                    .set(request.status() != null, UserAccountDO::getStatus,
                            request.status() == null ? null : request.status().code())
                    .set(UserAccountDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        }
        // 3. 数据库成功提交后注销全部登录；失败回滚不能把仍有权限的用户意外踢下线。
        // 对已停用账号再次执行停用也注销，确保这次明确的停用操作清掉可能残留的旧会话。
        if (typeChanged || request.status() == AccountStatus.DISABLED) {
            TransactionSynchronizationManager.registerSynchronization(new LogoutAfterCommit(accountId));
        }
    }

    /** 只有账号资料写入才锁定数据库账号；普通鉴权不经过这里。 */
    private UserAccountDO lockAccount(long accountId) {
        var account = userAccountMapper.selectForUpdate(accountId);
        if (account == null) {
            StpUtil.logout();
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED, SecurityMessageConstants.SESSION_EXPIRED);
        }
        if (account.getStatus() != AccountStatus.ENABLED.code()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
        }
        return account;
    }

    /** 只服务本类的简单 Spring 提交回调；回滚不会调用 afterCommit。 */
    private record LogoutAfterCommit(
            long accountId) implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            StpUtil.logout(accountId);
        }
    }

    private UserProfileDO findProfile(long accountId) {
        return userProfileMapper.selectOne(
                Wrappers.<UserProfileDO>lambdaQuery().eq(UserProfileDO::getAccountId, accountId));
    }

    /** 调用方已锁定账号行；同账号资料创建、昵称与头像更新均串行化。 */
    private UserProfileDO profileForWrite(long accountId) {
        UserProfileDO profile = findProfile(accountId);
        if (profile == null) {
            profile = new UserProfileDO();
            profile.setAccountId(accountId);
            profile.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        return profile;
    }

    private void saveProfile(UserProfileDO profile) {
        profile.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        int changed =
                profile.getId() == null
                        ? userProfileMapper.insert(profile)
                        : userProfileMapper.updateById(profile);
        if (changed != 1) {
            throw new ApiException(
                    ApiErrorCode.DATA_CONFLICT, AccountMessageConstants.ACCOUNT_WRITE_CONFLICT);
        }
    }

    private AccountProfileVo view(Account account, UserProfileDO profile) {
        return view(
                account,
                profile,
                assetQueryService
                        .findReady(
                                profile == null ? null : profile.getAvatarMediaId(),
                                AssetType.IMAGE)
                        .orElse(null));
    }

    private AccountProfileVo view(
            Account account, UserProfileDO profile, AssetResourceData avatar) {
        String nickname =
                profile == null || profile.getNickname() == null || profile.getNickname().isBlank()
                        ? account.username()
                        : profile.getNickname();
        return AccountConvert.MAPPER.to(account, nickname, avatar);
    }

    private String nickname(String value) {
        if (value == null || value.strip().isEmpty()) {
            return null;
        }
        String result = value.strip();
        if (result.codePointCount(0, result.length()) > MAX_NICKNAME_CHARACTERS
                || result.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(AccountMessageConstants.NICKNAME_INVALID);
        }
        return result;
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
