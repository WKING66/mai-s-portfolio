package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证宿主应用只通过 Starter 装配可复用基础设施组件。 */
class ModuleWiringTest {
    private static final String AUTO_CONFIGURATION_IMPORTS =
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void discoversFrameworkAutoConfigurationsFromStarters() throws IOException {
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
            .getResources(AUTO_CONFIGURATION_IMPORTS);
        List<String> autoConfigurations = new ArrayList<>();
        while (resources.hasMoreElements()) {
            try (var input = resources.nextElement().openStream();
                 var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                autoConfigurations.addAll(reader.lines().toList());
            }
        }

        assertThat(autoConfigurations)
            .contains("dev.amai.portfolio.web.WebAutoConfiguration")
            .contains("dev.amai.portfolio.logging.autoconfigure.ApiLogAutoConfiguration")
            .contains("dev.amai.portfolio.security.SecurityAutoConfiguration")
            .contains("dev.amai.portfolio.mybatis.autoconfigure.MybatisPlusAutoConfiguration")
            .contains("dev.amai.portfolio.datasource.DatasourceAutoConfiguration")
            .contains("dev.amai.portfolio.storage.autoconfigure.ObjectStorageAutoConfiguration");
    }
}
