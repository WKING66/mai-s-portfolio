package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.config.BootstrapProperties;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;

class SystemBootstrapServiceImplTest {
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);

    private SystemBootstrapServiceImpl service(String value) {
        return new SystemBootstrapServiceImpl(accounts, passwords, new BootstrapProperties(true, value));
    }
    @Test void rejectsPasswordsWhoseUtf8EncodingCannotBeEncrypted() {
        for (String value : new String[] {"a".repeat(191), "中".repeat(64)}) {
            assertThatThrownBy(() -> service(value).initialize()).isInstanceOf(IllegalStateException.class);
        }
        verify(passwords, never()).encode(anyString());
        verify(accounts, never()).insert(any(UserAccountDO.class));
    }
    @Test void acceptsProtocolBoundaryAndPreservesInitialPasswordVerbatim() {
        for (String value : new String[] {"a".repeat(190), "中".repeat(63), " initial-password "}) {
            service(value).initialize();
            verify(passwords).encode(value);
        }
    }
    @Test void existingOwnerIsNeverResetOrRevalidatedFromChangedEnvironment() {
        when(accounts.selectCount(any())).thenReturn(1L);
        service("a".repeat(191)).initialize();
        verifyNoInteractions(passwords);
        verify(accounts, never()).insert(any(UserAccountDO.class));
    }
}
