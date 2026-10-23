package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.component.AuthRequestValidator;
import dev.amai.portfolio.system.component.NewPasswordPolicy;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.convert.AccountConvert;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.entity.vo.RegistrationVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthServiceImpl implements AuthService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int CSRF_BYTES = 32;
    private static final Pattern REGISTER_USERNAME = Pattern.compile("[A-Za-z0-9_-]{3,64}");

    private final SecureRandom secureRandom = new SecureRandom();
    private final UserAccountMapper userAccountMapper;
    private final PasswordHasher passwordHasher;
    private final SecurityProperties securityProperties;
    private final PasswordCryptoService passwordCryptoService;
    private final LoginThrottleService loginThrottleService;
    private final NewPasswordPolicy newPasswordPolicy;
    private final AuthRequestValidator authRequestValidator;
    private final TransactionTemplate transactionTemplate;

    public AuthServiceImpl(
            UserAccountMapper userAccountMapper,
            PasswordHasher passwordHasher,
            SecurityProperties securityProperties,
            PasswordCryptoService passwordCryptoService,
            LoginThrottleService loginThrottleService,
            NewPasswordPolicy newPasswordPolicy,
            AuthRequestValidator authRequestValidator,
            PlatformTransactionManager transactionManager) {
        this.userAccountMapper = userAccountMapper;
        this.passwordHasher = passwordHasher;
        this.securityProperties = securityProperties;
        this.passwordCryptoService = passwordCryptoService;
        this.loginThrottleService = loginThrottleService;
        this.newPasswordPolicy = newPasswordPolicy;
        this.authRequestValidator = authRequestValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public SessionVo current() {
        if (!StpUtil.isLogin()) {
            return new SessionVo(false, null, null, List.of());
        }
        return AccountConvert.MAPPER.to(AccountContext.currentAccount(), true,
                (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY));
    }

    @Override
    public SessionVo login(String origin, String clientKey, LoginRequest input) {
        // 1. 先校验参数并占用登录额度；参数/来源/限流校验先于事务，非法请求不应依赖数据库连接。
        authRequestValidator.validate(input);
        // 在执行 RSA 解密和 Argon2 校验前占用额度，失败尝试也会进入固定窗口。
        loginThrottleService.acquireLoginPermit(clientKey);
        // 登录页面通过同源代理访问；显式核对 Origin，防止跨站表单建立受害者会话。
        if (origin == null || !securityProperties.allowedOrigins().contains(origin)) {
            LOG.warn("User login rejected, reason=ORIGIN_INVALID");
            throw new ApiException(
                    ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
        // 2. 来源校验通过后解密；只规范化用户名，登录密码保持原样匹配。
        String password = passwordCryptoService.decryptPassword(input.encryptedPassword());
        String username = input.username().trim().toLowerCase(Locale.ROOT);
        // 3. 在事务内锁定账号并校验密码，成功后建立必要的会话身份。
        return transactionTemplate.execute(status -> authenticateLocked(username, password));
    }

    private SessionVo authenticateLocked(String username, String password) {
        // PostgreSQL 默认区分大小写；查询语义须与 LOWER(username) 唯一索引一致。
        // 与改密共用账号行锁，避免使用被并发替换的密码创建会话。
        UserAccountDO account =
                userAccountMapper.selectOne(
                        Wrappers.<UserAccountDO>lambdaQuery()
                                .apply("LOWER(username) = {0}", username)
                                .last("FOR UPDATE"));
        if (account == null || !passwordHasher.matches(password, account.getPasswordHash())) {
            LOG.warn("User login rejected, reason=BAD_CREDENTIALS");
            throw new ApiException(
                    ApiErrorCode.AUTH_INVALID_CREDENTIALS, SystemMessageConstants.BAD_CREDENTIALS);
        }
        SessionVo session = startSession(account);
        // IP 窗口统计所有尝试；不能因某个账号成功而放行对其他账号的持续猜测。
        LOG.info("User logged in, accountId={}", account.getId());
        return session;
    }

    private SessionVo startSession(UserAccountDO account) {
        if (account.getStatus() != AccountStatus.ENABLED.code()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
        }
        // 角色在登录时由账号类型确定，后续请求直接读取同一份 Redis 身份。
        var roles = account.getType() == AccountType.OWNER.code()
                ? List.of(AuthConstants.OWNER_ROLE) : List.<String>of();
        var identity = AccountConvert.MAPPER.to(account, roles);
        StpUtil.login(account.getId(), new SaLoginParameter().setIsShare(false));
        AccountContext.set(identity);
        byte[] bytes = new byte[CSRF_BYTES];
        secureRandom.nextBytes(bytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StpUtil.getSession().set(AuthConstants.CSRF_SESSION_KEY, csrf);
        return AccountConvert.MAPPER.to(identity, true, csrf);
    }

    @Override
    public void logout() {
        long accountId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        LOG.info("User logged out, accountId={}", accountId);
    }

    @Override
    public RegistrationVo register(String origin, String clientKey, RegisterRequest input) {
        // 1. 校验请求、注册额度和来源，复用认证边界而不创建登录会话。
        authRequestValidator.validate(input);
        // 注册单独计数；复用 Redis 原子限流组件，避免注册防刷影响正常登录。
        loginThrottleService.acquireRegistrationPermit(clientKey);
        if (origin == null || !securityProperties.allowedOrigins().contains(origin)) {
            throw new ApiException(
                    ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
        // 2. 执行用户名规则及共享新密码政策；这些业务判断先于数据库事务。
        String username = input.username().trim();
        if (!REGISTER_USERNAME.matcher(username).matches()) {
            throw new ApiException(
                    ApiErrorCode.VALIDATION_FAILED,
                    SystemMessageConstants.REGISTER_USERNAME_INVALID);
        }
        String password =
                newPasswordPolicy.normalize(
                        passwordCryptoService.decryptPassword(input.encryptedPassword()));
        // 3. 事务内创建启用的普通账号，唯一索引兜底并发重名，不接受客户端角色。
        return transactionTemplate.execute(status -> createAccount(username, password));
    }

    private RegistrationVo createAccount(String username, String password) {
        // 1. 判重规则与 LOWER(username) 唯一索引一致，不改变用户展示名称的大小写。
        if (userAccountMapper.selectCount(
                        Wrappers.<UserAccountDO>lambdaQuery()
                                .apply("LOWER(username) = {0}", username.toLowerCase(Locale.ROOT)))
                > 0) {
            throw new ApiException(
                    ApiErrorCode.DATA_CONFLICT, SystemMessageConstants.REGISTER_USERNAME_EXISTS);
        }
        // 2. 持久化密码哈希和固定账号类型/状态；不保存明文或自动建会话。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAccountDO account = new UserAccountDO();
        account.setUsername(username);
        account.setType(AccountType.NORMAL.code());
        account.setStatus(AccountStatus.ENABLED.code());
        account.setPasswordHash(passwordHasher.encode(password));
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            userAccountMapper.insert(account);
        } catch (DuplicateKeyException conflict) {
            // 服务端先判重，唯一索引只处理并发竞争；异常越过事务边界后回滚。
            throw new ApiException(
                    ApiErrorCode.DATA_CONFLICT, SystemMessageConstants.REGISTER_USERNAME_EXISTS);
        }
        LOG.info("Normal account registered, accountId={}", account.getId());
        return new RegistrationVo(username);
    }

}
