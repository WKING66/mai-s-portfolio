package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.entity.vo.RegistrationVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.system.service.OwnerRoleService;
import java.util.List;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int CSRF_BYTES = 32;
    private static final Pattern REGISTER_USERNAME = Pattern.compile("[A-Za-z0-9_-]{3,64}");
    // 与前端 String.trim() 一致：ASCII 空白、Unicode 分隔符、BOM。
    private static final Pattern REGISTER_PASSWORD_PADDING = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    // 可见 ASCII：英文字母、数字、下划线及英文标点；不允许内部空白或控制字符。
    private static final Pattern REGISTER_PASSWORD_CHARACTERS = Pattern.compile("[\\x21-\\x7E]+");
    private static final int REGISTER_MIN_PASSWORD_CHARACTERS = 12;
    // RSA-2048 OAEP SHA-256 的单条明文上限，保持与 Web Crypto 一致。
    private static final int REGISTER_MAX_PASSWORD_BYTES = 190;

    private final UserAccountMapper accounts;
    private final PasswordHasher passwords;
    private final SecurityProperties security;
    private final PasswordCryptoService crypto;
    private final LoginThrottleService throttle;
    private final OwnerRoleService roles;
    private final SecureRandom random = new SecureRandom();

    public AuthServiceImpl(UserAccountMapper accounts, PasswordHasher passwords,
            SecurityProperties security, PasswordCryptoService crypto,
            LoginThrottleService throttle, OwnerRoleService roles) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.security = security;
        this.crypto = crypto;
        this.throttle = throttle;
        this.roles = roles;
    }

    @Override
    public SessionVo current() {
        if (!StpUtil.isLogin()) {
            return new SessionVo(false, null, null, List.of());
        }
        UserAccountDO account = requireActiveAccount();
        return new SessionVo(true, account.getUsername(),
            (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY),
            roles.findRoles(account.getId()));
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
        return new SessionVo(true, account.getUsername(), csrf, roles.findRoles(account.getId()));
    }

    @Override
    public void logout() {
        long accountId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        LOG.info("User logged out, accountId={}", accountId);
    }

    @Override
    @Transactional
    public RegistrationVo register(String origin, String clientKey, RegisterRequest input) {
        // 注册单独计数；复用 Redis 原子限流组件，避免注册防刷影响正常登录。
        throttle.acquireRegistrationPermit(clientKey);
        if (origin == null || !security.allowedOrigins().contains(origin)) {
            throw new ApiException(ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
        String username = input.username() == null ? "" : input.username().trim();
        if (!REGISTER_USERNAME.matcher(username).matches()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, SystemMessageConstants.REGISTER_USERNAME_INVALID);
        }
        String password = REGISTER_PASSWORD_PADDING.matcher(crypto.decryptPassword(input.encryptedPassword()))
            .replaceAll("");
        if (!password.isEmpty() && !REGISTER_PASSWORD_CHARACTERS.matcher(password).matches()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, SystemMessageConstants.REGISTER_PASSWORD_CHARACTERS_INVALID);
        }
        // 注册先去首尾空白，再按 Unicode code point 计数，而非 UTF-16 单元。
        if (password.codePointCount(0, password.length()) < REGISTER_MIN_PASSWORD_CHARACTERS
                || password.getBytes(StandardCharsets.UTF_8).length > REGISTER_MAX_PASSWORD_BYTES) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, SystemMessageConstants.REGISTER_PASSWORD_INVALID);
        }
        if (accounts.selectCount(Wrappers.<UserAccountDO>lambdaQuery()
                .apply("LOWER(username) = {0}", username.toLowerCase(Locale.ROOT))) > 0) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, SystemMessageConstants.REGISTER_USERNAME_EXISTS);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAccountDO account = new UserAccountDO();
        account.setUsername(username);
        account.setType(AccountType.NORMAL.code());
        account.setStatus(AccountStatus.ENABLED.code());
        account.setPasswordHash(passwords.encode(password));
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException conflict) {
            // 服务端先判重，唯一索引只处理并发竞争；异常越过事务边界后回滚。
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, SystemMessageConstants.REGISTER_USERNAME_EXISTS);
        }
        LOG.info("Normal account registered, accountId={}", account.getId());
        return new RegistrationVo(username);
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
