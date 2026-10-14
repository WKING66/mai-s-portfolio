package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.component.NewPasswordPolicy;
import dev.amai.portfolio.system.constant.AccountMessageConstants;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.domain.UserProfileDO;
import dev.amai.portfolio.system.entity.request.AccountAvatarRequest;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.mapper.UserProfileMapper;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import dev.amai.portfolio.system.service.AccountProfileService;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 本人资料维护与公开站长资料分离；不读取客户端账号 ID 或客户端权限字段。 */
@Service
public class AccountProfileServiceImpl implements AccountProfileService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountProfileServiceImpl.class);
    private static final int MAX_NICKNAME_CHARACTERS = 64;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final AssetQueryService assetQueryService;
    private final PasswordCryptoService passwordCryptoService;
    private final PasswordHasher passwordHasher;
    private final AccountAuthenticationService accountAuthenticationService;
    private final NewPasswordPolicy newPasswordPolicy;

    public AccountProfileServiceImpl(
            UserAccountMapper userAccountMapper,
            UserProfileMapper userProfileMapper,
            AssetQueryService assetQueryService,
            PasswordCryptoService passwordCryptoService,
            PasswordHasher passwordHasher,
            AccountAuthenticationService accountAuthenticationService,
            NewPasswordPolicy newPasswordPolicy) {
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.assetQueryService = assetQueryService;
        this.passwordCryptoService = passwordCryptoService;
        this.passwordHasher = passwordHasher;
        this.accountAuthenticationService = accountAuthenticationService;
        this.newPasswordPolicy = newPasswordPolicy;
    }

    @Override
    public AccountProfileVo current() {
        UserAccountDO account = activeAccount(false);
        return view(account, findProfile(account.getId()));
    }

    @Override
    @Transactional
    public AccountProfileVo update(AccountProfileRequest request) {
        // 1. 规范化昵称，再锁定当前账号，串行化同账号的资料创建与修改。
        String nickname = nickname(request.nickname());
        UserAccountDO account = activeAccount(true);
        UserProfileDO profile = profileForWrite(account.getId());
        // 2. 仅写本人昵称；头像等其他资料保持原样，响应通过资产查询生成。
        profile.setNickname(nickname);
        saveProfile(profile);
        return view(account, profile);
    }

    @Override
    @Transactional
    public AccountProfileVo bindAvatar(AccountAvatarRequest request) {
        // 1. 锁定本人账号后校验资产引用，禁止以请求数据选择其他账号。
        UserAccountDO account = activeAccount(true);
        if (request == null || request.assetId() == null || request.assetId() < 1) {
            throw invalid(AccountMessageConstants.AVATAR_BINDING_INVALID);
        }
        // 2. 只允许本人上传、AVATAR 用途且 READY 的图片；复用 Asset，不重复上传。
        var asset =
                assetQueryService
                        .findReadyMetadata(request.assetId())
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
        return view(account, profile);
    }

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
        UserAccountDO account = activeAccount(true);
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
        // 4. 密码与凭据版本同事务提交；回滚不失效会话，提交不依赖外部会话删除。
        long accountId = account.getId();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        LOG.info(
                                "Account password changed; prior credentials invalidated, accountId={}",
                                accountId);
                    }
                });
    }

    private UserAccountDO activeAccount(boolean forUpdate) {
        return forUpdate
                ? accountAuthenticationService.lockCurrentAccount()
                : accountAuthenticationService.currentAccount();
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

    private AccountProfileVo view(UserAccountDO account, UserProfileDO profile) {
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
            UserAccountDO account, UserProfileDO profile, AssetResourceData avatar) {
        String nickname =
                profile == null || profile.getNickname() == null || profile.getNickname().isBlank()
                        ? account.getUsername()
                        : profile.getNickname();
        return new AccountProfileVo(
                account.getUsername(),
                nickname,
                avatar == null ? null : avatar.resourceUrl(),
                avatar == null ? null : avatar.expiresAt());
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
