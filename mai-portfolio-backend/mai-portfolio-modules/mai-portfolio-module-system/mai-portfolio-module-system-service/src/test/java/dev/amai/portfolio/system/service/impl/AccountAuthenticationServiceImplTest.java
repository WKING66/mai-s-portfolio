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
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

/** 真实 Sa-Token、独立令牌及内存 DAO；不连接 Redis，验证版本生产与消费链路。 */
class AccountAuthenticationServiceImplTest {
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final AccountAuthenticationServiceImpl authentication = new AccountAuthenticationServiceImpl(accounts);
    private final UserAccountDO account = new UserAccountDO();
    private SaTokenConfig previousConfig;
    private SaTokenDao previousDao;
    private SaTokenContext previousContext;

    @BeforeEach void arrange() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        SaManager.setConfig(new SaTokenConfig().setTokenName("test-token").setIsShare(true));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        account.setId(7L);
        account.setStatus(AccountStatus.ENABLED.code());
        when(accounts.selectById(7L)).thenReturn(account);
        when(accounts.selectForUpdate(7L)).thenReturn(account);
        request(null);
    }
    @AfterEach void restore() {
        RequestContextHolder.resetRequestAttributes();
        SaManager.setConfig(previousConfig);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setSaTokenContext(previousContext);
    }
    private void request(String token) {
        var request = new MockHttpServletRequest();
        if (token != null) request.addHeader("test-token", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }
    private String login() {
        request(null);
        authentication.startSession(account);
        return StpUtil.getTokenValue();
    }
    private void expired(String token) {
        request(token);
        assertThatThrownBy(authentication::currentAccount).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(ApiErrorCode.UNAUTHENTICATED));
    }

    @Test void passwordCommitInvalidatesEveryOldTokenWithoutDependingOnRedisDeletion() {
        String first = login();
        String second = login();
        assertThat(first).isNotEqualTo(second); // 即使全局配置 isShare=true，新登录也不改写旧令牌。
        var memory = SaManager.getSaTokenDao();
        SaTokenDao failingDeletion = mock(SaTokenDao.class, org.mockito.AdditionalAnswers.delegatesTo(memory));
        doThrow(new IllegalStateException("simulated deletion outage")).when(failingDeletion).delete(anyString());
        SaManager.setSaTokenDao(failingDeletion);
        account.setCredentialVersion(1L); // 表示密码/版本事务已提交。
        expired(first);
        expired(second);
        String fresh = login();
        request(fresh);
        assertThat(authentication.currentAccount()).isSameAs(account);
        expired(first); // 新登录不会把旧 token 更新成新版本。
        verify(failingDeletion, never()).delete(anyString());
    }
    @Test void rollbackLeavesOldTokenValidBecauseDatabaseVersionIsUnchanged() {
        String token = login();
        request(token);
        assertThat(authentication.currentAccount()).isSameAs(account);
        assertThat(authentication.lockCurrentAccount()).isSameAs(account);
        verify(accounts).selectForUpdate(7L);
    }
    @Test void legacySessionWithoutVersionMustAuthenticateAgain() {
        StpUtil.login(7L);
        expired(StpUtil.getTokenValue());
    }
    @Test void unavailableSessionVersionStorageNeverReturnsAnAuthenticatedAccount() {
        String token = login();
        var memory = SaManager.getSaTokenDao();
        SaTokenDao failingReads = mock(SaTokenDao.class, org.mockito.AdditionalAnswers.delegatesTo(memory));
        doThrow(new IllegalStateException("simulated session read outage")).when(failingReads).getSession(anyString());
        SaManager.setSaTokenDao(failingReads);
        request(token);
        assertThatThrownBy(authentication::currentAccount).isInstanceOf(IllegalStateException.class);
    }
    @Test void disabledAccountCanLogOutEvenThoughItCannotAccessBusinessData() {
        String token = login();
        account.setStatus(AccountStatus.DISABLED.code());
        request(token);
        assertThatThrownBy(authentication::currentAccount).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(ApiErrorCode.FORBIDDEN));
        assertThat(authentication.logout()).isEqualTo(7L);
    }
    @Test void disabledAccountCannotEstablishASessionThroughTheAuthenticationBoundary() {
        account.setStatus(AccountStatus.DISABLED.code());
        assertThatThrownBy(() -> authentication.startSession(account)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(ApiErrorCode.FORBIDDEN));
        assertThat(authentication.findCurrentAccount()).isEmpty();
    }
    @Test void deletedAccountSessionProjectionRevokesTheTokenAndReturnsAnonymous() {
        String token = login();
        when(accounts.selectById(7L)).thenReturn(null);
        request(token);
        assertThat(authentication.findCurrentAccount()).isEmpty();
        request(token);
        assertThat(StpUtil.isLogin()).isFalse();
        assertThat(authentication.findCurrentAccount()).isEmpty();
    }
    @Test void deletedAccountCannotEnterBusinessAndTheTokenIsRevoked() {
        String token = login();
        when(accounts.selectById(7L)).thenReturn(null);
        request(token);
        assertThatThrownBy(authentication::currentAccount).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(ApiErrorCode.UNAUTHENTICATED));
        request(token);
        assertThat(StpUtil.isLogin()).isFalse();
    }
    @Test void disabledAccountSessionProjectionKeepsTheForbiddenContract() {
        String token = login();
        account.setStatus(AccountStatus.DISABLED.code());
        request(token);
        assertThatThrownBy(authentication::findCurrentAccount).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(ApiErrorCode.FORBIDDEN));
        assertThat(authentication.logout()).isEqualTo(7L);
    }
}
