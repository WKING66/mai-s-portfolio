package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.auth.AccountRoleProvider;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 使用真实 Sa-Token 和内存 DAO 验证会话逻辑；不冒充真实 Redis 集成。 */
class AccountAuthenticationServiceImplTest {
    private final UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);

    private final AccountAuthenticationServiceImpl authentication =
            new AccountAuthenticationServiceImpl(userAccountMapper);

    private final UserAccountDO account = new UserAccountDO();

    private SaTokenConfig previousConfig;

    private SaTokenDao previousDao;

    private SaTokenContext previousContext;

    @BeforeEach
    void arrange() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        SaManager.setConfig(new SaTokenConfig().setTokenName("test-token").setIsShare(true));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        account.setId(7L);
        account.setUsername("owner");
        account.setStatus(AccountStatus.ENABLED.code());
        account.setType(AccountType.OWNER.code());
        account.setPasswordHash("must-not-enter-session");
        account.setCredentialVersion(3L);
        request(null);
    }

    @AfterEach
    void restore() {
        RequestContextHolder.resetRequestAttributes();
        SaManager.setConfig(previousConfig);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setSaTokenContext(previousContext);
    }

    private void request(String token) {
        var request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("test-token", token);
        }
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    private String login() {
        request(null);
        authentication.startSession(account);
        return StpUtil.getTokenValue();
    }

    @Test
    void requestsAndRoleChecksUseSessionWithoutAnyAccountQuery() {
        String token = login();
        for (int i = 0; i < 3; i++) {
            request(token);
            assertThat(authentication.currentAccountId()).isEqualTo(7L);
            assertThat(authentication.currentAccount().getUsername()).isEqualTo("owner");
            assertThat(authentication.currentRoles()).containsExactly(AuthConstants.OWNER_ROLE);
            assertThat(new AccountRoleProvider().getRoleList("7", "login"))
                    .containsExactly(AuthConstants.OWNER_ROLE);
        }
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void sessionSnapshotExcludesPasswordAndDatabaseCredentialVersion() {
        String token = login();
        request(token);
        var snapshot = authentication.currentAccount();
        assertThat(snapshot).isNotSameAs(account);
        assertThat(snapshot.getPasswordHash()).isNull();
        assertThat(snapshot.getCredentialVersion()).isZero();
        assertThat(snapshot.getCreatedAt()).isNull();
        assertThat(snapshot.getUpdatedAt()).isNull();
    }

    @Test
    void databaseChangesDoNotTriggerHiddenRefreshAndReloginUpdatesRoles() {
        String token = login();
        account.setType(AccountType.NORMAL.code());
        account.setCredentialVersion(4L);
        request(token);
        assertThat(authentication.currentRoles()).containsExactly(AuthConstants.OWNER_ROLE);
        authentication.startSession(account);
        assertThat(authentication.currentRoles()).isEmpty();
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void explicitInvalidationRevokesAllExistingTokens() {
        String first = login();
        String second = login();
        assertThat(first).isNotEqualTo(second);
        authentication.invalidateSessions(7L);
        for (String token : java.util.List.of(first, second)) {
            request(token);
            assertThat(authentication.findCurrentAccount()).isEmpty();
        }
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void missingLegacyContextRequiresLoginWithoutDatabaseFallback() {
        StpUtil.login(7L);
        String token = StpUtil.getTokenValue();
        request(token);
        assertThatThrownBy(authentication::currentAccount).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.UNAUTHENTICATED));
        request(token);
        assertThat(authentication.findCurrentAccount()).isEmpty();
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void accountWritesStillReadAndLockActualAccount() {
        String token = login();
        when(userAccountMapper.selectForUpdate(7L)).thenReturn(account);
        request(token);
        assertThat(authentication.lockCurrentAccount()).isSameAs(account);
        verify(userAccountMapper).selectForUpdate(7L);
        verify(userAccountMapper, never()).selectById(anyLong());
        account.setStatus(AccountStatus.DISABLED.code());
        assertThatThrownBy(authentication::lockCurrentAccount).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.FORBIDDEN));
    }

    @Test
    void disabledAccountCannotCreateSessionAndLogoutRequiresNoDatabase() {
        account.setStatus(AccountStatus.DISABLED.code());
        assertThatThrownBy(() -> authentication.startSession(account)).isInstanceOf(ApiException.class);
        account.setStatus(AccountStatus.ENABLED.code());
        String token = login();
        request(token);
        assertThat(authentication.logout()).isEqualTo(7L);
        request(token);
        assertThat(authentication.findCurrentAccount()).isEmpty();
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void unavailableSessionStorageDoesNotFallBackToDatabase() {
        String token = login();
        SaTokenDao memory = SaManager.getSaTokenDao();
        SaTokenDao failing = mock(SaTokenDao.class, org.mockito.AdditionalAnswers.delegatesTo(memory));
        doThrow(new IllegalStateException("session unavailable")).when(failing).getSession(anyString());
        SaManager.setSaTokenDao(failing);
        request(token);
        assertThatThrownBy(authentication::currentAccount).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(userAccountMapper);
    }
}
