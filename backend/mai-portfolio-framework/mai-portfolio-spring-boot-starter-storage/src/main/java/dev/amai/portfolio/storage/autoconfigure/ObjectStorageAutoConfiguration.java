package dev.amai.portfolio.storage.autoconfigure;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.comm.Protocol;
import dev.amai.portfolio.storage.StorageMessageConstants;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.local.LocalObjectStorage;
import dev.amai.portfolio.storage.oss.AliyunOssObjectStorage;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/** 根据环境配置注册唯一的对象存储实现。 */
@AutoConfiguration
@EnableConfigurationProperties(MediaStorageProperties.class)
public class ObjectStorageAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "portfolio.media", name = "storage",
        havingValue = "local", matchIfMissing = true)
    ObjectStorage localObjectStorage(MediaStorageProperties properties) {
        return new LocalObjectStorage(properties.localDirectory());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "portfolio.media", name = "storage", havingValue = "oss")
    OSS ossClient(MediaStorageProperties properties) {
        MediaStorageProperties.Oss oss = properties.oss();
        String endpoint = validateOssConfiguration(oss);

        ClientBuilderConfiguration clientConfiguration = new ClientBuilderConfiguration();
        // SDK 的默认协议是 HTTP；即使未来调用方式变化，也强制使用 TLS。
        clientConfiguration.setProtocol(Protocol.HTTPS);
        clientConfiguration.setConnectionTimeout(Math.toIntExact(oss.connectTimeout().toMillis()));
        clientConfiguration.setSocketTimeout(Math.toIntExact(oss.socketTimeout().toMillis()));
        clientConfiguration.setMaxConnections(oss.maxConnections());
        return new OSSClientBuilder().build(
            endpoint, oss.accessKeyId(), oss.accessKeySecret(), clientConfiguration);
    }

    @Bean
    @ConditionalOnProperty(prefix = "portfolio.media", name = "storage", havingValue = "oss")
    ObjectStorage ossObjectStorage(OSS ossClient, MediaStorageProperties properties) {
        return new AliyunOssObjectStorage(ossClient, properties.oss().bucketName());
    }

    private String validateOssConfiguration(MediaStorageProperties.Oss oss) {
        if (isBlank(oss.endpoint()) || isBlank(oss.bucketName())
            || isBlank(oss.accessKeyId()) || isBlank(oss.accessKeySecret())) {
            throw new IllegalStateException(StorageMessageConstants.OSS_CONFIGURATION_INCOMPLETE);
        }
        return validateHttpsEndpoint(oss.endpoint());
    }

    private String validateHttpsEndpoint(String configuredEndpoint) {
        String endpoint = configuredEndpoint.trim();
        try {
            URI uri = new URI(endpoint);
            boolean rootPath = uri.getPath() == null || uri.getPath().isEmpty()
                || uri.getPath().equals("/");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || isBlank(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !rootPath) {
                throw new IllegalStateException(StorageMessageConstants.OSS_ENDPOINT_HTTPS_REQUIRED);
            }
            return endpoint;
        } catch (URISyntaxException error) {
            throw new IllegalStateException(StorageMessageConstants.OSS_ENDPOINT_HTTPS_REQUIRED, error);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
