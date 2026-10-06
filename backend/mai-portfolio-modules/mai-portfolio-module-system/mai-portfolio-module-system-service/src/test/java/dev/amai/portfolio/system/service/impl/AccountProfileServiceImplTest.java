package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.ImageAssetUploadService;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.entity.domain.UserProfileDO;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.mapper.UserProfileMapper;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 覆盖本人归属、并发更新边界和事务提交后的会话撤销，不以单测替代真实联调。 */
class AccountProfileServiceImplTest {
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final UserProfileMapper profiles = mock(UserProfileMapper.class);
    private final ImageAssetUploadService upload = mock(ImageAssetUploadService.class);
    private final AssetContentService assets = mock(AssetContentService.class);
    private final PasswordCryptoService crypto = mock(PasswordCryptoService.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final TestTransactionManager manager = new TestTransactionManager();
    private final TransactionTemplate transaction = new TransactionTemplate(manager);
    private final AccountProfileServiceImpl service = new AccountProfileServiceImpl(
        accounts, profiles, upload, assets, crypto, passwords, manager);
    private final UserAccountDO account = new UserAccountDO();

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "unit-test"),
            UserAccountDO.class);
    }

    @BeforeEach
    void arrange() {
        account.setId(7L);
        account.setUsername("normal_reader");
        account.setType(AccountType.NORMAL.code());
        account.setStatus(AccountStatus.ENABLED.code());
        account.setPasswordHash("old-hash");
        when(accounts.selectById(7L)).thenReturn(account);
        when(accounts.selectForUpdate(7L)).thenReturn(account);
        when(profiles.insert(any(UserProfileDO.class))).thenAnswer(invocation -> {
            invocation.<UserProfileDO>getArgument(0).setId(10L);
            return 1;
        });
        when(profiles.updateById(any(UserProfileDO.class))).thenReturn(1);
        when(accounts.update(isNull(), any())).thenReturn(1);
        when(upload.maxAvatarBytes()).thenReturn(2097152);
    }

    @Test
    void passwordChangeWithoutTransactionDoesNotTouchCredentialsOrDatabase() {
        assertThatThrownBy(() -> service.changePassword(new AccountPasswordRequest("old-cipher", "new-cipher")))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(accounts, crypto, passwords);
    }

    @Test
    void normalAccountReadsUsernameFallbackWithoutPersistingEmptyProfile() {
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var result = service.current();
            assertThat(result.username()).isEqualTo("normal_reader");
            assertThat(result.nickname()).isEqualTo("normal_reader");
            assertThat(result.avatarUrl()).isNull();
            verify(profiles, never()).insert(any(UserProfileDO.class));
            verifyNoInteractions(upload, crypto, passwords);
        }
    }

    @Test
    void nicknameWriteLocksAccountAndPreservesExistingAvatar() {
        UserProfileDO profile = existingProfile();
        when(profiles.selectOne(any())).thenReturn(profile);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var result = transaction.execute(status -> service.update(new AccountProfileRequest("  阿霾  ")));
            assertThat(result.nickname()).isEqualTo("阿霾");
            assertThat(result.avatarUrl()).isEqualTo("/api/v1/account/avatar?v=31");
        }
        verify(accounts).selectForUpdate(7L);
        verify(profiles).updateById(profile);
        assertThat(profile.getAvatarMediaId()).isEqualTo(31L);
    }

    @Test
    void emptyNicknameClearsExistingNicknameToUsernameFallback() {
        UserProfileDO profile = existingProfile();
        profile.setNickname("旧昵称");
        when(profiles.selectOne(any())).thenReturn(profile);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var result = transaction.execute(status -> service.update(new AccountProfileRequest("  ")));
            assertThat(result.nickname()).isEqualTo("normal_reader");
        }
        assertThat(profile.getNickname()).isNull();
        verify(profiles).updateById(profile);
    }

    @Test
    void unicodeNicknameUsesCodePointsRatherThanUtf16Length() {
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThat(transaction.execute(status -> service.update(new AccountProfileRequest("😀".repeat(64))))
                .nickname()).hasSize(128);
            assertThatThrownBy(() -> transaction.execute(status ->
                service.update(new AccountProfileRequest("😀".repeat(65)))))
                .isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"含\n换行", "含\t制表", "含\u0000控制"})
    void nicknameRejectsEmbeddedControls(String nickname) {
        assertThatThrownBy(() -> service.update(new AccountProfileRequest(nickname)))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(profiles, upload, assets);
    }

    @Test
    void avatarUploadOccursBeforeBindingTransactionAndUsesOnlyCurrentAccount() {
        when(upload.uploadAvatar(any(), eq("image/png"))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return 31L;
        });
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var result = service.uploadAvatar(new MockMultipartFile("file", "private/path.png",
                "image/png", new byte[] {1, 2, 3}));
            assertThat(result.avatarUrl()).isEqualTo("/api/v1/account/avatar?v=31");
        }
        var saved = ArgumentCaptor.forClass(UserProfileDO.class);
        verify(profiles).insert(saved.capture());
        assertThat(saved.getValue().getAccountId()).isEqualTo(7L);
        assertThat(saved.getValue().getAvatarMediaId()).isEqualTo(31L);
        verify(accounts).selectForUpdate(7L);
    }

    @Test
    void avatarUploadFailureNeverWritesProfile() {
        when(upload.uploadAvatar(any(), any())).thenThrow(new ApiException(
            ApiErrorCode.INTERNAL_ERROR, "safe-upload-error"));
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> service.uploadAvatar(new MockMultipartFile("file",
                "avatar.png", "image/png", new byte[] {1}))).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(profiles);
        verify(accounts, never()).selectForUpdate(any(Long.class));
    }

    @Test
    void avatarReceiverCapsInputBeforeAssetProvider() {
        when(upload.maxAvatarBytes()).thenReturn(3);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> service.uploadAvatar(new MockMultipartFile("file",
                "avatar.png", "image/png", new byte[] {1, 2, 3, 4})))
                .isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        }
        verify(upload, never()).uploadAvatar(any(), any());
        verifyNoInteractions(profiles);
    }

    @Test
    void avatarReadCanOnlyOpenCurrentProfileReference() throws Exception {
        when(profiles.selectOne(any())).thenReturn(existingProfile());
        var content = new AssetContentData(new ByteArrayInputStream(new byte[] {1}), 1,
            "image/png", "avatar.png", AssetType.IMAGE);
        when(assets.openReady(31L, AssetType.IMAGE)).thenReturn(Optional.of(content));
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            try (var result = service.openAvatar()) {
                assertThat(result).isSameAs(content);
            }
        }
        verify(assets).openReady(31L, AssetType.IMAGE);
    }

    @Test
    void passwordChangeCommitsBeforeRevokingAllAccountSessionsAndTrimsOnlyNewValue() {
        when(crypto.decryptPassword("old-cipher")).thenReturn(" old-password ");
        when(crypto.decryptPassword("new-cipher")).thenReturn(" \u00A0new-password-ab!\uFEFF ");
        when(passwords.matches(" old-password ", "old-hash")).thenReturn(true);
        when(passwords.encode("new-password-ab!")).thenReturn("new-hash");
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            transaction.executeWithoutResult(status -> {
                service.changePassword(new AccountPasswordRequest("old-cipher", "new-cipher"));
                stp.verify(() -> StpUtil.logout(7L), never());
            });
            stp.verify(() -> StpUtil.logout(7L));
        }
        verify(passwords).matches(" old-password ", "old-hash");
        verify(passwords).encode("new-password-ab!");
        verify(accounts).selectForUpdate(7L);
        assertThat(manager.commits).isEqualTo(1);
    }

    @Test
    void rolledBackPasswordChangeDoesNotRevokeSessions() {
        validPasswordInput();
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            transaction.executeWithoutResult(status -> {
                service.changePassword(new AccountPasswordRequest("old-cipher", "new-cipher"));
                status.setRollbackOnly();
            });
            stp.verify(() -> StpUtil.logout(7L), never());
        }
        assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test
    void wrongCurrentPasswordIsValidationErrorAndPreservesSession() {
        validPasswordInput();
        when(passwords.matches(any(), any())).thenReturn(false);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.changePassword(
                new AccountPasswordRequest("old-cipher", "new-cipher"))))
                .isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
            stp.verify(() -> StpUtil.logout(7L), never());
        }
        verify(accounts, never()).update(isNull(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inside space-ab!", "含中文new-password!", "short-ab!", "newline\npassword-ab!"})
    void newPasswordReusesRegistrationRules(String password) {
        when(crypto.decryptPassword("old-cipher")).thenReturn("old-password-ab!");
        when(crypto.decryptPassword("new-cipher")).thenReturn(password);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.changePassword(
            new AccountPasswordRequest("old-cipher", "new-cipher"))))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        verify(accounts, never()).update(isNull(), any());
    }

    @Test
    void conditionalHashConflictReturns409AndNeverRevokesSessions() {
        validPasswordInput();
        when(accounts.update(isNull(), any())).thenReturn(0);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.changePassword(
                new AccountPasswordRequest("old-cipher", "new-cipher"))))
                .isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ApiErrorCode.DATA_CONFLICT));
            stp.verify(() -> StpUtil.logout(7L), never());
        }
    }

    @Test
    void disabledAccountCannotReadOrModifyProfile() {
        account.setStatus(AccountStatus.DISABLED.code());
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(service::current).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> transaction.execute(status -> service.update(new AccountProfileRequest("新昵称"))))
                .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(profiles, upload, assets);
    }

    private UserProfileDO existingProfile() {
        UserProfileDO profile = new UserProfileDO();
        profile.setId(10L);
        profile.setAccountId(7L);
        profile.setNickname("阿霾");
        profile.setAvatarMediaId(31L);
        return profile;
    }

    private void validPasswordInput() {
        when(crypto.decryptPassword("old-cipher")).thenReturn("old-password-ab!");
        when(crypto.decryptPassword("new-cipher")).thenReturn("new-password-ab!");
        when(passwords.matches("old-password-ab!", "old-hash")).thenReturn(true);
        when(passwords.encode("new-password-ab!")).thenReturn("new-hash");
    }

    /** 只模拟 Spring 提交/回滚及同步回调时机，不模拟数据库 SQL 语义。 */
    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
