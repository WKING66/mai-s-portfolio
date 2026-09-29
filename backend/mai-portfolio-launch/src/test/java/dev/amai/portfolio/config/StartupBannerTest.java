package dev.amai.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StartupBannerTest {
    private final StartupBanner banner = new StartupBanner();

    @Test
    void includesActualPortAndDocumentationAddressesWhenEnabled() {
        assertThat(banner.formatBanner(9333, "", true, false))
            .contains("启动成功", "http://127.0.0.1:9333/doc.html",
                "http://127.0.0.1:9333/v3/api-docs");
    }

    @Test
    void doesNotAdvertiseDisabledDocumentation() {
        assertThat(banner.formatBanner(8443, "/portfolio", false, true))
            .contains("未开放").doesNotContain("/doc.html", "/v3/api-docs");
        assertThat(banner.formatBanner(8443, "/portfolio", true, true))
            .contains("https://127.0.0.1:8443/portfolio/doc.html");
    }
}
