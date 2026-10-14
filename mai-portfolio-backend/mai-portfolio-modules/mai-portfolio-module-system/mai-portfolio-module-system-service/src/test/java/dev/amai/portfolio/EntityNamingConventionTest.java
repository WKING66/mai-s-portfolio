package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 锁定 HTTP 入参与返回模型的目录和后缀，防止后续再次混放。 */
class EntityNamingConventionTest {
    private static final Path ENTITY_ROOT =
        Path.of("src/main/java/dev/amai/portfolio/system/entity");

    @Test
    void requestEntitiesUseRequestSuffixAndVoEntitiesUseVoSuffix() throws IOException {
        assertJavaFileSuffix(ENTITY_ROOT.resolve("request"), "Request.java");
        assertJavaFileSuffix(ENTITY_ROOT.resolve("vo"), "Vo.java");
        assertThat(ENTITY_ROOT.resolve("envelope")).doesNotExist();
    }

    private void assertJavaFileSuffix(Path directory, String suffix) throws IOException {
        assertThat(directory).isDirectory();
        try (var paths = Files.list(directory)) {
            List<String> invalidFiles = paths
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.endsWith(suffix))
                .toList();
            assertThat(invalidFiles).isEmpty();
        }
    }
}
