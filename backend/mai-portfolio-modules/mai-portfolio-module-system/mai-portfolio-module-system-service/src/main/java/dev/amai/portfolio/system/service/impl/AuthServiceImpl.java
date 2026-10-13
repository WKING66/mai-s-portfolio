package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.component.NewPasswordPolicy;
import dev.amai.portfolio.system.component.AuthRequestValidator;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
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
import java.util.Locale;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthServiceImpl implements AuthService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final Pattern REGISTER_USERNAME = Pattern.compile("[A-Za-z0-9_-]{3,64}");

    private final UserAccountMapper accounts;
    private final PasswordHasher passwords;
    private final SecurityProperties security;
    private final PasswordCryptoService crypto;
    private final LoginThrottleService throttle;
    private final OwnerRoleService roles;
    private final AccountAuthenticationService authentication;
    private final NewPasswordPolicy newPasswords;
    private final AuthRequestValidator requests;
    private final TransactionTemplate transactions;

    public AuthServiceImpl(UserAccountMapper accounts, PasswordHasher passwords,
            SecurityProperties security, PasswordCryptoService crypto,
            LoginThrottleService throttle, OwnerRoleService roles,
            AccountAuthenticationService authentication,
            NewPasswordPolicy newPasswords, AuthRequestValidator requests,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.security = security;
        this.crypto = crypto;
        this.throttle = throttle;
        this.roles = roles;
        this.authentication = authentication;
        this.newPasswords = newPasswords;
        this.requests = requests;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public SessionVo current() {
        UserAccountDO account = authentication.findCurrentAccount().orElse(null);
        if (account == null) {
            return new SessionVo(false, null, null, List.of());
        }
        return new SessionVo(true, account.getUsername(),
            authentication.csrfToken(),
            roles.findRoles(account.getId()));
    }

    @Override
    public SessionVo login(String origin, String clientKey, LoginRequest input) {
        // 参数/来源/限流校验先于事务，非法请求不应依赖数据库连接。
        requests.validate(input);
        // 在执行 RSA 解密和 Argon2 校验前占用额度，失败尝试也会进入固定窗口。
        throttle.acquireLoginPermit(clientKey);
        // 登录页面通过同源代理访问；显式核对 Origin，防止跨站表单建立受害者会话。
        if (origin == null || !security.allowedOrigins().contains(origin)) {
            LOG.warn("User login rejected, reason=ORIGIN_INVALID");
            throw new ApiException(ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
        String password = crypto.decryptPassword(input.encryptedPassword());
        String username = input.username().trim().toLowerCase(Locale.ROOT);
        return transactions.execute(status -> authenticateLocked(username, password));
    }

    private SessionVo authenticateLocked(String username, String password) {
        // PostgreSQL 默认区分大小写；查询语义须与 LOWER(username) 唯一索引一致。
        // 与改密共用账号行锁，令牌必须绑定与所验证密码一致的已持久化凭据版本。
        UserAccountDO account = accounts.selectOne(Wrappers.<UserAccountDO>lambdaQuery()
            .apply("LOWER(username) = {0}", username)
            .last("FOR UPDATE"));
        if (account == null || !passwords.matches(password, account.getPasswordHash())) {
            LOG.warn("User login rejected, reason=BAD_CREDENTIALS");
            throw new ApiException(ApiErrorCode.AUTH_INVALID_CREDENTIALS, SystemMessageConstants.BAD_CREDENTIALS);
        }
        String csrf = authentication.startSession(account);
        // IP 窗口统计所有尝试；不能因某个账号成功而放行对其他账号的持续猜测。
        LOG.info("User logged in, accountId={}", account.getId());
        return new SessionVo(true, account.getUsername(), csrf, roles.findRoles(account.getId()));
    }

    @Override
    public void logout() {
        long accountId = authentication.logout();
        LOG.info("User logged out, accountId={}", accountId);
    }

    @Override
    public RegistrationVo register(String origin, String clientKey, RegisterRequest input) {
        requests.validate(input);
        // 注册单独计数；复用 Redis 原子限流组件，避免注册防刷影响正常登录。
        throttle.acquireRegistrationPermit(clientKey);
        if (origin == null || !security.allowedOrigins().contains(origin)) {
            throw new ApiException(ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
        String username = input.username().trim();
        if (!REGISTER_USERNAME.matcher(username).matches()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, SystemMessageConstants.REGISTER_USERNAME_INVALID);
        }
        String password = newPasswords.normalize(crypto.decryptPassword(input.encryptedPassword()));
        return transactions.execute(status -> createAccount(username, password));
    }

    private RegistrationVo createAccount(String username, String password) {
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
        authentication.currentAccount();
    }

}
