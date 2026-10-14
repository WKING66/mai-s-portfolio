package dev.amai.portfolio.system.controller;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.auth.AuthRequestExceptionHandler;
import dev.amai.portfolio.system.component.AuthRequestValidator;
import dev.amai.portfolio.system.component.NewPasswordPolicy;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.exception.InvalidCredentialPayloadException;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.system.service.OwnerRoleService;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.system.service.impl.AuthServiceImpl;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.entity.vo.RegistrationVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class AuthContractTest {
    private static final String ORIGIN = "https://example.test";
    private final ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final PasswordCryptoService crypto = mock(PasswordCryptoService.class);
    private final LoginThrottleService throttle = mock(LoginThrottleService.class);
    private final AccountAuthenticationService sessions = mock(AccountAuthenticationService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AuthService auth = spy(new AuthServiceImpl(accounts, passwords,
        new SecurityProperties(List.of(ORIGIN), new ByteArrayResource(new byte[0]),
            false, 5, Duration.ofMinutes(10), 5, Duration.ofMinutes(5)), crypto, throttle,
        mock(OwnerRoleService.class), sessions, new NewPasswordPolicy(),
        new AuthRequestValidator(validators.getValidator()), transactions));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth), new OtherController())
        .setControllerAdvice(new AuthRequestExceptionHandler(), new GlobalExceptionHandler()).build();

    @AfterEach void closeValidators() { validators.close(); }
    @BeforeEach void configureTransaction() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test void loginRegistrationAndAnonymousSessionKeepDistinctRoutes() throws Exception {
        doReturn(new RegistrationVo("visitor")).when(auth).register(any(), any(), any());
        doReturn(new SessionVo(true, "visitor", "csrf", List.of())).when(auth).login(any(), any(), any());
        doReturn(new SessionVo(false, null, null, List.of())).when(auth).current();
        mvc.perform(post("/api/v1/auth/register").header("Origin", "https://example.test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"visitor\",\"encryptedPassword\":\"cipher\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("visitor"));
        verify(auth).register(eq("https://example.test"), any(), any(RegisterRequest.class));
        mvc.perform(post("/api/v1/auth/session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"visitor\",\"encryptedPassword\":\"cipher\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedIn").value(true));
        mvc.perform(get("/api/v1/auth/session")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.loggedIn").value(false));
        mvc.perform(delete("/api/v1/auth/session")).andExpect(status().isOk());
        verify(auth).logout();
    }
    @Test void credentialValidationUsesDedicatedExceptionButOtherBusinessValidationStillUses422() throws Exception {
        for (String route : List.of("/api/v1/auth/session", "/api/v1/auth/register")) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIAL_PAYLOAD"))
                .andExpect(jsonPath("$.message").value("认证请求参数不合法"))
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(result -> assertThat(result.getResolvedException())
                    .isInstanceOf(InvalidCredentialPayloadException.class));
        }
        mvc.perform(post("/test/business").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(accounts, passwords, crypto, throttle, sessions, transactions);
    }

    @Test void malformedJsonUsesProtocolErrorRatherThanCredentialError() throws Exception {
        for (String route : List.of("/api/v1/auth/session", "/api/v1/auth/register")) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(auth);
    }

    @Test void oversizedCredentialFieldIsRejectedWithoutEchoingItsValue() throws Exception {
        String ciphertext = "sensitive-".repeat(60);
        for (String route : List.of("/api/v1/auth/session", "/api/v1/auth/register")) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"visitor\",\"encryptedPassword\":\"" + ciphertext + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(ciphertext))
                .andExpect(result -> assertThat(result.getResolvedException())
                    .isInstanceOf(InvalidCredentialPayloadException.class));
        }
        verifyNoInteractions(accounts, passwords, crypto, throttle, sessions, transactions);
    }

    @Test void realBusinessFailuresRemainDistinctFromRequestValidation() throws Exception {
        when(crypto.decryptPassword("cipher")).thenReturn("valid-password-2026!");
        mvc.perform(post("/api/v1/auth/session").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"unknown\",\"encryptedPassword\":\"cipher\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/register").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bad@name\",\"encryptedPassword\":\"cipher\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(crypto.decryptPassword("cipher")).thenReturn("short");
        mvc.perform(post("/api/v1/auth/register").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"visitor\",\"encryptedPassword\":\"cipher\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(crypto.decryptPassword("cipher")).thenReturn("valid-password-2026!");
        when(accounts.selectCount(any())).thenReturn(1L);
        mvc.perform(post("/api/v1/auth/register").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"visitor\",\"encryptedPassword\":\"cipher\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
        verify(accounts, never()).insert(any(dev.amai.portfolio.system.entity.domain.UserAccountDO.class));
        verifyNoInteractions(passwords, sessions);
    }
    @RestController static class OtherController {
        record Input(@NotBlank String name) { }
        @PostMapping("/test/business") R<Void> save(@Valid @RequestBody Input input) { return R.success(null); }
    }
}
