package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.system.service.LoginThrottleService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int CSRF_BYTES = 32;

    private final UserAccountMapper accounts;
    private final PasswordHasher passwords;
    private final SecurityProperties security;
    private final PasswordCryptoService crypto;
    private final LoginThrottleService throttle;
    private final SecureRandom random = new SecureRandom();

    public AuthServiceImpl(UserAccountMapper accounts, PasswordHasher passwords,
            SecurityProperties security, PasswordCryptoService crypto,
            LoginThrottleService throttle) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.security = security;
        this.crypto = crypto;
        this.throttle = throttle;
    }

    @Override
    public SessionVo current() {
        if (!StpUtil.isLogin()) {
            return new SessionVo(false, null, null);
        }
        UserAccountDO account = requireActiveAccount();
        return new SessionVo(true, account.getUsername(),
            (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY));
    }

    @Override
    public SessionVo login(String origin, String clientKey, LoginRequest input) {
        // 在执行 RSA 解密和 Argon2 校验前占用额度，失败尝试也会进入固定窗口。
        throttle.acquireLoginPermit(clientKey);
        // 登录页面通过同源代理访问；显式核对 Origin，防止跨站表单建立受害者会话。
        if (origin == null || !security.allowedOrigins().contains(origin)) {
            LOG.warn("User login rejected, reason=ORIGIN_INVALID");
            throw new ApiException(ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
        String password = crypto.decryptPassword(input.encryptedPassword());
        // PostgreSQL 默认区分大小写；查询语义须与 LOWER(username) 唯一索引一致。
        UserAccountDO account = accounts.selectOne(Wrappers.<UserAccountDO>lambdaQuery()
            .apply("LOWER(username) = {0}", input.username().toLowerCase(Locale.ROOT)));
        if (account == null || !passwords.matches(password, account.getPasswordHash())) {
            LOG.warn("User login rejected, reason=BAD_CREDENTIALS");
            throw new ApiException(ApiErrorCode.AUTH_INVALID_CREDENTIALS, SystemMessageConstants.BAD_CREDENTIALS);
        }
        if (account.getStatus() != AccountStatus.ENABLED.code()) {
            LOG.warn("User login rejected, reason=FORBIDDEN, accountId={}", account.getId());
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
        }
        StpUtil.login(account.getId());
        // 每次登录重新生成随机令牌，只在该会话中校验后续写请求。
        byte[] bytes = new byte[CSRF_BYTES];
        random.nextBytes(bytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StpUtil.getSession().set(AuthConstants.CSRF_SESSION_KEY, csrf);
        // IP 窗口统计所有尝试；不能因某个账号成功而放行对其他账号的持续猜测。
        LOG.info("User logged in, accountId={}", account.getId());
        return new SessionVo(true, account.getUsername(), csrf);
    }

    @Override
    public void logout() {
        long accountId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        LOG.info("User logged out, accountId={}", accountId);
    }

    @Override
    public void checkActiveAccount() {
        requireActiveAccount();
    }

    /** 每次受保护请求读取当前状态；角色变更由独立的 OWNER 授权检查处理。 */
    private UserAccountDO requireActiveAccount() {
        StpUtil.checkLogin();
        UserAccountDO account = accounts.selectById(StpUtil.getLoginIdAsLong());
        if (account == null) {
            StpUtil.logout();
        }
        if (account == null || account.getStatus() != AccountStatus.ENABLED.code()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
        }
        return account;
    }
}
