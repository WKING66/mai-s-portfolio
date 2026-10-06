package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.ImageAssetUploadService;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.constant.AccountMessageConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.domain.UserProfileDO;
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
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 本人资料维护与公开站长资料分离；不读取客户端账号 ID 或客户端权限字段。 */
@Service
public class AccountProfileServiceImpl implements AccountProfileService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountProfileServiceImpl.class);
    private static final int MAX_NICKNAME_CHARACTERS = 64;
    private static final int MIN_PASSWORD_CHARACTERS = 12;
    private static final int MAX_PASSWORD_CHARACTERS = 190;
    private static final Pattern PASSWORD_PADDING = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    private static final Pattern PASSWORD_CHARACTERS = Pattern.compile("[\\x21-\\x7E]+");
    private final UserAccountMapper accounts;
    private final UserProfileMapper profiles;
    private final ImageAssetUploadService upload;
    private final AssetContentService assets;
    private final PasswordCryptoService crypto;
    private final PasswordHasher passwords;
    private final TransactionTemplate transactions;

    public AccountProfileServiceImpl(UserAccountMapper accounts, UserProfileMapper profiles,
            ImageAssetUploadService upload, AssetContentService assets, PasswordCryptoService crypto,
            PasswordHasher passwords, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.upload = upload;
        this.assets = assets;
        this.crypto = crypto;
        this.passwords = passwords;
        this.transactions = new TransactionTemplate(transactionManager);
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
    public AccountProfileVo uploadAvatar(MultipartFile file) {
        // 在外部上传前确认身份，上传完成后短事务中重新确认，防止中途账号被停用。
        UserAccountDO account = activeAccount(false);
        if (file == null || file.isEmpty()) {
            throw invalid(AccountMessageConstants.AVATAR_READ_FAILED);
        }
        byte[] bytes;
        try (InputStream input = file.getInputStream()) {
            bytes = input.readNBytes(upload.maxAvatarBytes() + 1);
        } catch (IOException readFailure) {
            throw invalid(AccountMessageConstants.AVATAR_READ_FAILED);
        }
        if (bytes.length > upload.maxAvatarBytes()) {
            throw invalid(AccountMessageConstants.AVATAR_TOO_LARGE);
        }
        long assetId = upload.uploadAvatar(bytes, file.getContentType());
        // OSS 与 DB 无跨资源原子事务；绑定失败时新资产仍有记录，旧头像保持不变。
        return transactions.execute(status -> {
            UserAccountDO locked = activeAccount(true);
            if (!locked.getId().equals(account.getId())) {
                throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
            }
            UserProfileDO profile = profileForWrite(account.getId());
            profile.setAvatarMediaId(assetId);
            saveProfile(profile);
            return view(locked, profile);
        });
    }

    @Override
    public AssetContentData openAvatar() {
        UserAccountDO account = activeAccount(false);
        UserProfileDO profile = findProfile(account.getId());
        Long assetId = profile == null ? null : profile.getAvatarMediaId();
        // URL 的 v 仅为刷新标记；授权从会话到本人资料引用，再交给 Asset 校验类型/READY。
        return assets.openReady(assetId, AssetType.IMAGE)
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, AccountMessageConstants.AVATAR_NOT_FOUND));
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
        String newPassword = normalizedNewPassword(crypto.decryptPassword(request.newEncryptedPassword()));
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
            .set(UserAccountDO::getPasswordHash, passwords.encode(newPassword))
            .set(UserAccountDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, AccountMessageConstants.ACCOUNT_WRITE_CONFLICT);
        }
        // 不能在回滚前销毁会话；账户行锁与旧 hash 条件防止并发改密重复成功。
        long accountId = account.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                StpUtil.logout(accountId);
                LOG.info("Account password changed and sessions revoked, accountId={}", accountId);
            }
        });
    }

    private UserAccountDO activeAccount(boolean forUpdate) {
        StpUtil.checkLogin();
        long accountId = StpUtil.getLoginIdAsLong();
        UserAccountDO account = forUpdate ? accounts.selectForUpdate(accountId) : accounts.selectById(accountId);
        if (account == null || account.getStatus() != AccountStatus.ENABLED.code()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
        }
        return account;
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
        String nickname = profile == null || profile.getNickname() == null || profile.getNickname().isBlank()
            ? account.getUsername() : profile.getNickname();
        String avatar = profile == null || profile.getAvatarMediaId() == null ? null
            : AuthConstants.ACCOUNT_PATH + "/avatar?v=" + profile.getAvatarMediaId();
        return new AccountProfileVo(account.getUsername(), nickname, avatar);
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

    private String normalizedNewPassword(String value) {
        String password = PASSWORD_PADDING.matcher(value).replaceAll("");
        if (!password.isEmpty() && !PASSWORD_CHARACTERS.matcher(password).matches()) {
            throw invalid(SystemMessageConstants.REGISTER_PASSWORD_CHARACTERS_INVALID);
        }
        if (password.length() < MIN_PASSWORD_CHARACTERS || password.length() > MAX_PASSWORD_CHARACTERS) {
            throw invalid(SystemMessageConstants.REGISTER_PASSWORD_INVALID);
        }
        return password;
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
