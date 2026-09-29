package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.vo.LoginChallengeVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.AdminSessionService;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.service.LoginChallengeService;
import dev.amai.portfolio.system.service.LoginThrottleService;
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
    private final PasswordHasher passwords;
    private final SecurityProperties security;
    private final LoginChallengeService challenges;
    private final LoginThrottleService throttle;
    private final SecureRandom random = new SecureRandom();

    public AdminSessionServiceImpl(UserAccountMapper accounts, PasswordHasher passwords,
            SecurityProperties security, LoginChallengeService challenges,
            LoginThrottleService throttle) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.security = security;
        this.challenges = challenges;
        this.throttle = throttle;
    }

    @Override
    public LoginChallengeVo issueChallenge(String clientKey) {
        return challenges.issueChallenge(clientKey);
    }

    @Override
    public SessionVo current() {
        if (!StpUtil.isLogin()) {
            return new SessionVo(false, null, null);
        }
        StpUtil.checkRole(AuthConstants.OWNER_ROLE);
        UserAccountDO owner = accounts.selectById(StpUtil.getLoginIdAsLong());
        if (owner == null) {
            StpUtil.logout();
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ADMIN_FORBIDDEN);
        }
        return new SessionVo(true, owner.getUsername(),
            (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY));
    }

    @Override
    public SessionVo login(String origin, String clientKey, LoginRequest input) {
        // 在执行 RSA 解密和 Argon2 校验前占用额度，失败尝试也会进入固定窗口。
        throttle.acquireLoginPermit(clientKey);
        // 管理端通过同源代理访问；显式核对 Origin，防止跨站表单建立受害者会话。
        if (origin == null || !security.allowedOrigins().contains(origin)) {
            LOG.warn("Admin login rejected, reason=ORIGIN_INVALID");
            throw new ApiException(ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
        String password = challenges.consumePassword(clientKey, input.challengeId(), input.encryptedPassword());
        // PostgreSQL 默认区分大小写；查询语义须与 LOWER(username) 唯一索引一致。
        UserAccountDO account = accounts.selectOne(Wrappers.<UserAccountDO>lambdaQuery()
            .apply("LOWER(username) = {0}", input.username().toLowerCase(Locale.ROOT)));
        if (account == null || !passwords.matches(password, account.getPasswordHash())) {
            LOG.warn("Admin login rejected, reason=BAD_CREDENTIALS");
            throw new ApiException(ApiErrorCode.BAD_CREDENTIALS, SystemMessageConstants.BAD_CREDENTIALS);
        }
        if (account.getType() != AccountType.OWNER.code()
                || account.getStatus() != AccountStatus.ENABLED.code()) {
            LOG.warn("Admin login rejected, reason=FORBIDDEN, accountId={}", account.getId());
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ADMIN_FORBIDDEN);
        }
        StpUtil.login(account.getId());
        // 每次登录重新生成随机令牌，只在该会话中校验后续写请求。
        byte[] bytes = new byte[CSRF_BYTES];
        random.nextBytes(bytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StpUtil.getSession().set(AuthConstants.CSRF_SESSION_KEY, csrf);
        throttle.recordLoginSuccess(clientKey);
        LOG.info("Admin logged in, accountId={}", account.getId());
        return new SessionVo(true, account.getUsername(), csrf);
    }

    @Override
    public void logout() {
        long ownerId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        LOG.info("Admin logged out, accountId={}", ownerId);
    }
}
