package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.system.service.OwnerRoleService;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;

/** 注册复用现有认证组件；只验证新行为，不重写登录测试。 */
class RegistrationServiceTest {
    private static final String ORIGIN = "http://127.0.0.1:3004";
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final PasswordCryptoService crypto = mock(PasswordCryptoService.class);
    private final LoginThrottleService throttle = mock(LoginThrottleService.class);
    private final OwnerRoleService roles = mock(OwnerRoleService.class);
    private final AuthServiceImpl auth = new AuthServiceImpl(accounts, passwords,
        new SecurityProperties(List.of(ORIGIN), new ByteArrayResource(new byte[0]),
            false, 5, Duration.ofMinutes(10), 5, Duration.ofMinutes(5)), crypto, throttle, roles);

    @Test
    void createsOnlyEnabledNormalAccountWithArgonHashAndNoSession() {
        String rawPassword = "  test-password  ";
        when(crypto.decryptPassword("cipher")).thenReturn(rawPassword);
        when(passwords.encode(rawPassword.trim())).thenReturn("argon-hash");
        try (var stp = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            assertThat(auth.register(ORIGIN, "client", new RegisterRequest("  New_visitor  ", "cipher"))
                .username()).isEqualTo("New_visitor");
            stp.verifyNoInteractions();
        }
        var account = ArgumentCaptor.forClass(UserAccountDO.class);
        verify(accounts).insert(account.capture());
        assertThat(account.getValue().getId()).isNull();
        assertThat(account.getValue().getUsername()).isEqualTo("New_visitor");
        assertThat(account.getValue().getType()).isEqualTo(AccountType.NORMAL.code());
        assertThat(account.getValue().getStatus()).isEqualTo(AccountStatus.ENABLED.code());
        assertThat(account.getValue().getPasswordHash()).isEqualTo("argon-hash");
        assertThat(account.getValue().getCreatedAt()).isEqualTo(account.getValue().getUpdatedAt());
        verifyNoInteractions(roles);
        verify(throttle).acquireRegistrationPermit("client");
        verify(throttle, never()).acquireLoginPermit(any());
        verify(passwords).encode(rawPassword.trim());
        verify(passwords, never()).encode(rawPassword);
    }

    @ParameterizedTest
    @ValueSource(strings = {"            ", " \t\nshort\u00A0", " ABCdefghi90 "})
    void validatesLengthAfterRemovingPasswordPadding(String password) {
        when(crypto.decryptPassword("cipher")).thenReturn(password);
        expect(ApiErrorCode.VALIDATION_FAILED, () -> auth.register(ORIGIN, "client",
            new RegisterRequest("visitor", "cipher")));
        verifyNoInteractions(accounts, passwords);
    }

    @Test
    void removesUnicodePaddingBeforeHashing() {
        when(crypto.decryptPassword("cipher")).thenReturn("\uFEFF\u00A0  test-password-2026 \u3000\t");
        auth.register(ORIGIN, "client", new RegisterRequest("visitor", "cipher"));
        verify(passwords).encode("test-password-2026");
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal password", "test\tpassword-2026", "test\npassword-2026",
        "test\u00A0password-2026", "test\u3000password-2026", "test\u0000password-2026"})
    void rejectsInternalWhitespaceBeforePersistence(String password) {
        when(crypto.decryptPassword("cipher")).thenReturn(password);
        expect(ApiErrorCode.VALIDATION_FAILED, () -> auth.register(ORIGIN, "client",
            new RegisterRequest("visitor", "cipher")));
        verifyNoInteractions(accounts, passwords);
    }

    @Test
    void validatesOriginBeforeDecryptingOrWriting() {
        expect(ApiErrorCode.ORIGIN_INVALID, () -> auth.register("https://outside.test", "client",
            new RegisterRequest("visitor", "cipher")));
        expect(ApiErrorCode.ORIGIN_INVALID, () -> auth.register(null, "client",
            new RegisterRequest("visitor", "cipher")));
        verifyNoInteractions(crypto, passwords, accounts);
    }

    @Test
    void rateLimitRunsBeforeExpensiveCrypto() {
        doThrow(new ApiException(ApiErrorCode.RATE_LIMITED, "test-rate-limit"))
            .when(throttle).acquireRegistrationPermit("client");
        expect(ApiErrorCode.RATE_LIMITED, () -> auth.register(ORIGIN, "client",
            new RegisterRequest("visitor", "cipher")));
        verifyNoInteractions(crypto, passwords, accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "名字测试", "has space", "has@symbol", ""})
    void rejectsInvalidUsernameWithoutPersistence(String username) {
        expect(ApiErrorCode.VALIDATION_FAILED, () -> auth.register(ORIGIN, "client",
            new RegisterRequest(username, "cipher")));
        verifyNoInteractions(accounts, passwords);
    }

    @ParameterizedTest
    @ValueSource(strings = {"中文密码测试-2026", "😀test-password", "test-password！"})
    void rejectsNonAsciiCharactersWithoutPersistence(String password) {
        when(crypto.decryptPassword("cipher")).thenReturn(password);
        expect(ApiErrorCode.VALIDATION_FAILED, () -> auth.register(ORIGIN, "client",
            new RegisterRequest("visitor", "cipher")));
        verifyNoInteractions(accounts, passwords);
    }

    @Test
    void acceptsAllVisibleAsciiCharacters() {
        StringBuilder characters = new StringBuilder();
        for (char character = 33; character <= 126; character++) characters.append(character);
        String password = characters.toString();
        when(crypto.decryptPassword("cipher")).thenReturn(password);
        auth.register(ORIGIN, "client", new RegisterRequest("visitor", "cipher"));
        verify(passwords).encode(password);
    }

    @Test
    void rejectsOversizedPasswordBeforeHashing() {
        when(crypto.decryptPassword("cipher")).thenReturn("a".repeat(191));
        expect(ApiErrorCode.VALIDATION_FAILED, () -> auth.register(ORIGIN, "client",
            new RegisterRequest("visitor", "cipher")));
        verifyNoInteractions(accounts, passwords);
    }

    @Test
    void duplicatePrecheckDoesNotHashPassword() {
        when(crypto.decryptPassword("cipher")).thenReturn("test-password-2026");
        when(accounts.selectCount(any())).thenReturn(1L);
        expect(ApiErrorCode.DATA_CONFLICT, () -> auth.register(ORIGIN, "client",
            new RegisterRequest("OWNER", "cipher")));
        verifyNoInteractions(passwords);
        verify(accounts, never()).insert(any(UserAccountDO.class));
    }

    @Test
    void concurrentUniqueIndexConflictUsesStableBusinessError() {
        when(crypto.decryptPassword("cipher")).thenReturn("test-password-2026");
        when(accounts.insert(any(UserAccountDO.class))).thenThrow(new DuplicateKeyException("test-index"));
        expect(ApiErrorCode.DATA_CONFLICT, () -> auth.register(ORIGIN, "client",
            new RegisterRequest("visitor", "cipher")));
    }

    private void expect(ApiErrorCode code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(code));
    }
}
