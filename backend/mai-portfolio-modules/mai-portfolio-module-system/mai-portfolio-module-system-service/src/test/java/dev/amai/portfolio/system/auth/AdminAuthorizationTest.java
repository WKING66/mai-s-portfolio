package dev.amai.portfolio.system.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthorizationTest {
    private final AdminAuthorization authorization = new AdminAuthorization();

    @Test
    void permitsLoginChallengeWhenApplicationUsesContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/portfolio/api/v1/admin/session/challenge");
        request.setContextPath("/portfolio");

        boolean permitted = authorization.preHandle(
            request, new MockHttpServletResponse(), new Object());

        assertThat(permitted).isTrue();
    }
}
