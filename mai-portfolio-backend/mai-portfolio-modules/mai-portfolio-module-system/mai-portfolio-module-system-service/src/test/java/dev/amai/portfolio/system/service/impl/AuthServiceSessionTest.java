package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.component.AuthRequestValidator;
import dev.amai.portfolio.system.component.NewPasswordPolicy;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthServiceSessionTest {
    private static final String ORIGIN = "https://example.test";
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final PasswordCryptoService crypto = mock(PasswordCryptoService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AuthServiceImpl service = new AuthServiceImpl(accounts, passwords,
            new SecurityProperties(List.of(ORIGIN), new ByteArrayResource(new byte[0]),
                    false, 5, Duration.ofMinutes(10), 5, Duration.ofMinutes(5)),
            crypto, mock(LoginThrottleService.class), new NewPasswordPolicy(),
            mock(AuthRequestValidator.class), transactions);
    private SaTokenConfig previousConfig;
    private SaTokenDao previousDao;
    private SaTokenContext previousContext;
    private UserAccountDO account;

    @BeforeEach
    void arrange() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        SaManager.setConfig(new SaTokenConfig().setTokenName("login-token"));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        request(null);
        account = new UserAccountDO();
        account.setId(7L);
        account.setUsername("owner");
        account.setType(AccountType.OWNER.code());
        account.setStatus(AccountStatus.ENABLED.code());
        account.setPasswordHash("private-password-hash");
        when(accounts.selectOne(any())).thenReturn(account);
        when(crypto.decryptPassword("cipher")).thenReturn("password");
        when(passwords.matches("password", account.getPasswordHash())).thenReturn(true);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @AfterEach
    void restore() {
        RequestContextHolder.resetRequestAttributes();
        SaManager.setConfig(previousConfig);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setSaTokenContext(previousContext);
    }

    @Test
    void loginStoresOnlyIdentityAndRestoresSessionWithoutReadingAccounts() {
        var login = service.login(ORIGIN, "browser", new LoginRequest("owner", "cipher"));
        assertThat(login.loggedIn()).isTrue();
        assertThat(login.roles()).containsExactly("OWNER");
        assertThat(login.csrfToken()).isNotBlank();
        assertThat(StpUtil.getSession().get("account")).isNull();
        String token = StpUtil.getTokenValue();
        clearInvocations(accounts, passwords, crypto);
        request(token);

        assertThat(service.current()).isEqualTo(login);
        assertThat(AccountContext.currentAccount().username()).isEqualTo("owner");
        assertThat(AccountContext.roles(7L)).containsExactly("OWNER");
        service.logout();
        request(token);
        assertThat(service.current().loggedIn()).isFalse();
        verifyNoInteractions(accounts, passwords, crypto);
    }

    @Test
    void normalAccountDoesNotAcquireOwnerRole() {
        account.setType(AccountType.NORMAL.code());
        var login = service.login(ORIGIN, "browser", new LoginRequest("owner", "cipher"));

        assertThat(login.roles()).isEmpty();
        assertThat(AccountContext.currentAccount().roles()).isEmpty();
    }

    @Test
    void disabledAccountCannotCreateSession() {
        account.setStatus(AccountStatus.DISABLED.code());

        assertThatThrownBy(() -> service.login(ORIGIN, "browser", new LoginRequest("owner", "cipher")))
                .isInstanceOf(ApiException.class);
        assertThat(StpUtil.isLogin()).isFalse();
    }

    @Test
    void anonymousSessionDoesNotReadDatabase() {
        assertThat(service.current().loggedIn()).isFalse();
        verifyNoInteractions(accounts, passwords, crypto);
    }

    private void request(String token) {
        var request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("login-token", token);
        }
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }
}
