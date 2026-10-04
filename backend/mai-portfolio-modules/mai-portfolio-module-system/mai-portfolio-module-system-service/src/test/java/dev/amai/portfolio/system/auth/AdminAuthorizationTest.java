package dev.amai.portfolio.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthorizationTest {
    @Test
    void ownerAuthorizationDoesNotDependOnLoginEndpointExceptions() {
        try (var stp = mockStatic(StpUtil.class)) {
            assertThat(new AdminAuthorization().preHandle(
                new MockHttpServletRequest("GET", "/api/v1/admin/profile"),
                new MockHttpServletResponse(), new Object())).isTrue();
            stp.verify(StpUtil::checkLogin);
            stp.verify(() -> StpUtil.checkRole(AuthConstants.OWNER_ROLE));
        }
    }
}
