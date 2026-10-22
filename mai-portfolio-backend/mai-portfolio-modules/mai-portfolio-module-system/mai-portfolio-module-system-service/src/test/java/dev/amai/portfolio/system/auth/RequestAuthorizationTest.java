package dev.amai.portfolio.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.security.SaTokenExceptionHandler;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.api.OwnerView;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.impl.AccountAuthenticationServiceImpl;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestAuthorizationTest {
    private final UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);

    private final AccountAuthenticationServiceImpl authentication =
            new AccountAuthenticationServiceImpl(userAccountMapper);

    private final AccountRoleProvider roles = spy(new AccountRoleProvider());

    private SaTokenConfig previousConfig;

    private SaTokenDao previousDao;

    private SaTokenContext previousContext;

    private StpInterface previousRoles;

    private MockMvc mvc;

    @BeforeEach
    void arrange() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        previousRoles = SaManager.getStpInterface();
        SaManager.setConfig(new SaTokenConfig().setTokenName("test-token"));
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        SaManager.setStpInterface(roles);
        var policy = new RequestSecurityPolicy(new SecurityProperties(List.of("https://example.test"),
                new ByteArrayResource(new byte[0]), true, 5, Duration.ofMinutes(10),
                5, Duration.ofMinutes(5)));
        mvc = MockMvcBuilders.standaloneSetup(new Endpoints(authentication))
                .addInterceptors(new AuthenticationInterceptor(policy, authentication), new SaInterceptor())
                .setControllerAdvice(new SaTokenExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void restore() {
        RequestContextHolder.resetRequestAttributes();
        SaManager.setConfig(previousConfig);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setSaTokenContext(previousContext);
        SaManager.setStpInterface(previousRoles);
    }

    private String login(AccountType type) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
        var account = new UserAccountDO();
        account.setId(7L);
        account.setUsername("visitor");
        account.setStatus(AccountStatus.ENABLED.code());
        account.setType(type.code());
        authentication.startSession(account);
        String token = StpUtil.getTokenValue();
        RequestContextHolder.resetRequestAttributes();
        clearInvocations(roles);
        return token;
    }

    @Test
    void staticOwnerEndpointChecksRoleOnceAndNeverQueriesAccountTable() throws Exception {
        String token = login(AccountType.OWNER);
        mvc.perform(get("/api/v1/check/owner").secure(true).header("test-token", token))
                .andExpect(status().isOk()).andExpect(content().string("7"));
        verify(roles).getRoleList(any(), any());
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void anonymousPublicViewStaysPublicAndManageRequiresOwner() throws Exception {
        mvc.perform(get("/api/v1/check/views")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/check/views").param("view", "MANAGE").secure(true))
                .andExpect(status().isUnauthorized());
        String token = login(AccountType.NORMAL);
        mvc.perform(get("/api/v1/check/views").param("view", "MANAGE")
                .secure(true).header("test-token", token)).andExpect(status().isForbidden());
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void manageViewRequiresHttpsAndChecksRoleOnce() throws Exception {
        String token = login(AccountType.OWNER);
        mvc.perform(get("/api/v1/check/views").param("view", "MANAGE")
                .header("test-token", token).header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(roles);
        mvc.perform(get("/api/v1/check/views").param("view", "MANAGE")
                .secure(true).header("test-token", token)).andExpect(status().isOk());
        verify(roles).getRoleList(any(), any());
        verifyNoInteractions(userAccountMapper);
    }

    @Test
    void writesStillRejectMissingCsrf() throws Exception {
        String token = login(AccountType.OWNER);
        mvc.perform(post("/api/v1/check/write").secure(true).header("test-token", token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userAccountMapper);
    }

    @RestController
    static class Endpoints {
        private final AccountAuthenticationServiceImpl authentication;

        Endpoints(AccountAuthenticationServiceImpl authentication) {
            this.authentication = authentication;
        }

        @SaCheckRole(AuthConstants.OWNER_ROLE)
        @GetMapping("/api/v1/check/owner")
        public long owner() {
            return authentication.currentAccountId();
        }

        @OwnerView
        @GetMapping("/api/v1/check/views")
        public String views() {
            return "view";
        }

        @SaCheckRole(AuthConstants.OWNER_ROLE)
        @PostMapping("/api/v1/check/write")
        public String write() {
            return "ok";
        }
    }
}
