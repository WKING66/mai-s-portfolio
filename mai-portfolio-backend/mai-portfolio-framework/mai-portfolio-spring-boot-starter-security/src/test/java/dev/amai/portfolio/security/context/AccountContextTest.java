package dev.amai.portfolio.security.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AccountContextTest {
    private SaTokenConfig previousConfig;
    private SaTokenDao previousDao;
    private SaTokenContext previousContext;

    @BeforeEach
    void arrange() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        SaManager.setConfig(new SaTokenConfig().setTokenName("context-token"));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        request(null);
    }

    @AfterEach
    void restore() {
        RequestContextHolder.resetRequestAttributes();
        SaManager.setConfig(previousConfig);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setSaTokenContext(previousContext);
    }

    @Test
    void readsNecessaryIdentityFromExistingSessionAcrossRequests() {
        StpUtil.login(7L);
        var account = new AccountContext.Account(7L, "owner", List.of("OWNER"));
        AccountContext.set(account);
        String token = StpUtil.getTokenValue();
        request(token);

        assertThat(AccountContext.currentAccount()).isEqualTo(account);
        assertThat(AccountContext.roles(7L)).containsExactly("OWNER");
        assertThat(AccountContext.roles(8L)).isEmpty();
    }

    @Test
    void legacySessionWithoutIdentityRequiresLoginAgain() {
        StpUtil.login(7L);
        StpUtil.getSession().set("account", "legacy");
        String token = StpUtil.getTokenValue();
        request(token);

        assertThatThrownBy(AccountContext::currentAccount).isInstanceOf(ApiException.class);
        request(token);
        assertThat(StpUtil.isLogin()).isFalse();
    }

    @Test
    void mismatchedIdentityCannotBeUsedAsCurrentAccount() {
        StpUtil.login(7L);
        AccountContext.set(new AccountContext.Account(8L, "another", List.of("OWNER")));
        request(StpUtil.getTokenValue());

        assertThat(AccountContext.roles(7L)).isEmpty();
        assertThatThrownBy(AccountContext::currentAccount).isInstanceOf(ApiException.class);
    }

    @Test
    void revokedSessionCannotRestoreIdentity() {
        StpUtil.login(7L);
        AccountContext.set(new AccountContext.Account(7L, "owner", List.of("OWNER")));
        String token = StpUtil.getTokenValue();
        StpUtil.logout(7L);
        request(token);

        assertThat(StpUtil.isLogin()).isFalse();
        assertThat(AccountContext.roles(7L)).isEmpty();
        assertThatThrownBy(AccountContext::currentAccount).isInstanceOf(RuntimeException.class);
    }

    private void request(String token) {
        var request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("context-token", token);
        }
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }
}
