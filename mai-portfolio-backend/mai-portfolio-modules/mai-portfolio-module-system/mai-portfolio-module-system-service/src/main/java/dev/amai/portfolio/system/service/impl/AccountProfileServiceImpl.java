package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.component.NewPasswordPolicy;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.system.entity.request.AccountAvatarRequest;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.constant.AccountMessageConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.domain.UserProfileDO;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.mapper.UserProfileMapper;
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
    private final UserAccountMapper accounts;
    private final UserProfileMapper profiles;
    private final AssetQueryService assets;
    private final PasswordCryptoService crypto;
    private final PasswordHasher passwords;
    private final AccountAuthenticationService authentication;
    private final NewPasswordPolicy newPasswords;

    public AccountProfileServiceImpl(UserAccountMapper accounts, UserProfileMapper profiles,
            AssetQueryService assets, PasswordCryptoService crypto,
            PasswordHasher passwords,
            AccountAuthenticationService authentication,
            NewPasswordPolicy newPasswords) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.assets = assets;
        this.crypto = crypto;
        this.passwords = passwords;
        this.authentication = authentication;
        this.newPasswords = newPasswords;
    }

    @Override
    public AccountProfileVo current() {
        UserAccountDO account = activeAccount(false);
        return view(account, findProfile(account.getId()));
    }

    @Override
    @Transactional
    public AccountProfileVo update(AccountProfileRequest request) {
        String nickname = nickname(request.nickname());
        UserAccountDO account = activeAccount(true);
        UserProfileDO profile = profileForWrite(account.getId());
        profile.setNickname(nickname);
        saveProfile(profile);
        return view(account, profile);
    }

    @Override
    @Transactional
    public AccountProfileVo bindAvatar(AccountAvatarRequest request) {
        UserAccountDO account = activeAccount(true);
        if (request == null || request.assetId() == null || request.assetId() < 1) {
            throw invalid(AccountMessageConstants.AVATAR_BINDING_INVALID);
        }
        var asset = assets.findReadyMetadata(request.assetId())
            .filter(resource -> resource.type() == AssetType.IMAGE && resource.usage() == AssetUsage.AVATAR
                && account.getId().equals(resource.uploaderAccountId()))
            .orElseThrow(() -> invalid(AccountMessageConstants.AVATAR_BINDING_INVALID));
        UserProfileDO profile = profileForWrite(account.getId());
        profile.setAvatarMediaId(asset.id());
        saveProfile(profile);
        return view(account, profile);
    }

    @Override
    @Transactional
    public void changePassword(AccountPasswordRequest request) {
        // 在任何持久化修改前检查事务，避免非代理调用误把自动提交写入当成安全改密。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(AccountMessageConstants.PASSWORD_TRANSACTION_REQUIRED);
        }
        String oldPassword = crypto.decryptPassword(request.oldEncryptedPassword());
        String newPassword = newPasswords.normalize(crypto.decryptPassword(request.newEncryptedPassword()));
        UserAccountDO account = activeAccount(true);
        if (!passwords.matches(oldPassword, account.getPasswordHash())) {
            // 422 保留当前登录态，不能把“旧密码输错”误当成会话失效的 401。
            throw invalid(AccountMessageConstants.CURRENT_PASSWORD_INVALID);
        }
        if (oldPassword.equals(newPassword)) {
            throw invalid(AccountMessageConstants.PASSWORD_UNCHANGED);
        }
        int changed = accounts.update(null, Wrappers.<UserAccountDO>lambdaUpdate()
            .eq(UserAccountDO::getId, account.getId())
            .eq(UserAccountDO::getPasswordHash, account.getPasswordHash())
            .eq(UserAccountDO::getCredentialVersion, account.getCredentialVersion())
            .set(UserAccountDO::getCredentialVersion, Math.addExact(account.getCredentialVersion(), 1L))
            .set(UserAccountDO::getPasswordHash, passwords.encode(newPassword))
            .set(UserAccountDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, AccountMessageConstants.ACCOUNT_WRITE_CONFLICT);
        }
        // 密码与凭据版本同事务提交；回滚不失效会话，提交不依赖外部会话删除。
        long accountId = account.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOG.info("Account password changed; prior credentials invalidated, accountId={}", accountId);
            }
        });
    }

    private UserAccountDO activeAccount(boolean forUpdate) {
        return forUpdate ? authentication.lockCurrentAccount() : authentication.currentAccount();
    }

    private UserProfileDO findProfile(long accountId) {
        return profiles.selectOne(Wrappers.<UserProfileDO>lambdaQuery().eq(UserProfileDO::getAccountId, accountId));
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
        int changed = profile.getId() == null ? profiles.insert(profile) : profiles.updateById(profile);
        if (changed != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, AccountMessageConstants.ACCOUNT_WRITE_CONFLICT);
        }
    }

    private AccountProfileVo view(UserAccountDO account, UserProfileDO profile) {
        return view(account, profile, assets.findReady(profile == null ? null : profile.getAvatarMediaId(), AssetType.IMAGE).orElse(null));
    }

    private AccountProfileVo view(UserAccountDO account, UserProfileDO profile, AssetResourceData avatar) {
        String nickname = profile == null || profile.getNickname() == null || profile.getNickname().isBlank()
            ? account.getUsername() : profile.getNickname();
        return new AccountProfileVo(account.getUsername(), nickname,
            avatar == null ? null : avatar.resourceUrl(), avatar == null ? null : avatar.expiresAt());
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
