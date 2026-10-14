package dev.amai.portfolio.storage.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.oss.OSS;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.local.LocalObjectStorage;
import dev.amai.portfolio.storage.oss.AliyunOssObjectStorage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObjectStorageAutoConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void localModeCreatesOnlyLocalObjectStorage() {
        contextRunner()
            .withPropertyValues(
                "portfolio.media.storage=local",
                "portfolio.media.local-directory=" + directory.toString().replace('\\', '/'))
            .run(context -> {
                assertThat(context).hasSingleBean(ObjectStorage.class);
                assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(LocalObjectStorage.class);
            });
    }

    @Test
    void ossModeFailsFastWhenCredentialsAreMissing() {
        contextRunner()
            .withPropertyValues(
                "portfolio.media.storage=oss",
                "portfolio.media.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
                "portfolio.media.oss.bucket-name=portfolio-private")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void ossModeRejectsEndpointWithoutHttpsScheme() {
        contextRunner()
            .withPropertyValues(
                "portfolio.media.storage=oss",
                "portfolio.media.oss.endpoint=oss-cn-hangzhou.aliyuncs.com",
                "portfolio.media.oss.bucket-name=portfolio-private",
                "portfolio.media.oss.access-key-id=test-access-key",
                "portfolio.media.oss.access-key-secret=test-access-secret")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void ossModeCreatesOnlyAliyunObjectStorageWhenConfigurationIsComplete() {
        contextRunner()
            .withPropertyValues(
                "portfolio.media.storage=oss",
                "portfolio.media.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
                "portfolio.media.oss.bucket-name=portfolio-private",
                "portfolio.media.oss.access-key-id=test-access-key",
                "portfolio.media.oss.access-key-secret=test-access-secret")
            .run(context -> {
                assertThat(context).hasSingleBean(OSS.class);
                assertThat(context).hasSingleBean(ObjectStorage.class);
                assertThat(context.getBean(ObjectStorage.class))
                    .isInstanceOf(AliyunOssObjectStorage.class);
            });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ObjectStorageAutoConfiguration.class);
    }
}
