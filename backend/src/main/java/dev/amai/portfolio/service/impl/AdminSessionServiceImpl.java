package dev.amai.portfolio.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.common.AuthConstants;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.common.ApiErrorCode;
import dev.amai.portfolio.common.ApiException;
import dev.amai.portfolio.common.MessageConstants;
import dev.amai.portfolio.config.SecurityProperties;
import dev.amai.portfolio.dto.LoginRequest;
import dev.amai.portfolio.dto.LoginChallengeResponse;
import dev.amai.portfolio.dto.SessionResponse;
import dev.amai.portfolio.entity.UserAccountEntity;
import dev.amai.portfolio.enums.AccountStatus;
import dev.amai.portfolio.enums.AccountType;
import dev.amai.portfolio.mapper.UserAccountMapper;
import dev.amai.portfolio.service.AdminSessionService;
import dev.amai.portfolio.service.OwnerAccessService;
import dev.amai.portfolio.service.OwnerIdentity;
import dev.amai.portfolio.service.PasswordService;
import dev.amai.portfolio.service.LoginChallengeService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminSessionServiceImpl implements AdminSessionService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminSessionServiceImpl.class);
    private static final int CSRF_BYTES = 32;

    private final UserAccountMapper accounts;
    private final PasswordService passwords;
    private final OwnerAccessService owners;
    private final SecurityProperties security;
    private final LoginChallengeService challenges;
    private final SecureRandom random = new SecureRandom();

    public AdminSessionServiceImpl(UserAccountMapper accounts, PasswordService passwords,
            OwnerAccessService owners, SecurityProperties security, LoginChallengeService challenges) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.owners = owners;
        this.security = security;
        this.challenges = challenges;
    }

    @Override
    public LoginChallengeResponse issueChallenge() {
        return challenges.issueChallenge();
    }

    @Override
    public SessionResponse current() {
        if (!StpUtil.isLogin()) {
            return new SessionResponse(false, null, null);
        }
        OwnerIdentity owner = owners.requireOwner();
        return new SessionResponse(true, owner.username(),
            (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY));
    }

    @Override
    public SessionResponse login(String origin, LoginRequest input) {
        // 管理端通过同源代理访问；显式核对 Origin，防止跨站表单建立受害者会话。
        if (origin == null || !security.allowedOrigins().contains(origin)) {
            LOG.warn("Admin login rejected, reason=ORIGIN_INVALID");
            throw new ApiException(ApiErrorCode.ORIGIN_INVALID, MessageConstants.ORIGIN_INVALID);
        }
        String password = challenges.consumePassword(input.challengeId(), input.encryptedPassword());
        // PostgreSQL 默认区分大小写；查询语义须与 LOWER(username) 唯一索引一致。
        UserAccountEntity account = accounts.selectOne(Wrappers.<UserAccountEntity>lambdaQuery()
            .apply("LOWER(username) = {0}", input.username().toLowerCase(Locale.ROOT)));
        if (account == null || !passwords.matches(password, account.getPasswordHash())) {
            LOG.warn("Admin login rejected, reason=BAD_CREDENTIALS");
            throw new ApiException(ApiErrorCode.BAD_CREDENTIALS, MessageConstants.BAD_CREDENTIALS);
        }
        if (account.getType() != AccountType.OWNER.code()
                || account.getStatus() != AccountStatus.ENABLED.code()) {
            LOG.warn("Admin login rejected, reason=FORBIDDEN, accountId={}", account.getId());
            throw new ApiException(ApiErrorCode.FORBIDDEN, MessageConstants.ADMIN_FORBIDDEN);
        }
        StpUtil.login(account.getId());
        // 每次登录重新生成随机令牌，只在该会话中校验后续写请求。
        byte[] bytes = new byte[CSRF_BYTES];
        random.nextBytes(bytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StpUtil.getSession().set(AuthConstants.CSRF_SESSION_KEY, csrf);
        LOG.info("Admin logged in, accountId={}", account.getId());
        return new SessionResponse(true, account.getUsername(), csrf);
    }

    @Override
    public void logout() {
        long ownerId = owners.requireOwner().id();
        StpUtil.logout();
        LOG.info("Admin logged out, accountId={}", ownerId);
    }
}
