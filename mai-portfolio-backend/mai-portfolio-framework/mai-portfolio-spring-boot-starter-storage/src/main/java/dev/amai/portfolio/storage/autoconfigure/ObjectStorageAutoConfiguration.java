package dev.amai.portfolio.storage.autoconfigure;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.comm.Protocol;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.StorageMessageConstants;
import dev.amai.portfolio.storage.local.LocalObjectStorage;
import dev.amai.portfolio.storage.oss.AliyunOssObjectStorage;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 根据环境配置注册唯一的对象存储实现。 */
@AutoConfiguration
@EnableConfigurationProperties(MediaStorageProperties.class)
public class ObjectStorageAutoConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "portfolio.media",
            name = "storage",
            havingValue = "local",
            matchIfMissing = true)
    ObjectStorage localObjectStorage(MediaStorageProperties mediaStorageProperties) {
        return new LocalObjectStorage(mediaStorageProperties.localDirectory());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "portfolio.media", name = "storage", havingValue = "oss")
    OSS ossClient(MediaStorageProperties mediaStorageProperties) {
        // 1. 按现有规则检查配置完整性与 HTTPS 根地址。
        MediaStorageProperties.Oss ossProperties = mediaStorageProperties.oss();
        String endpoint = validateOssConfiguration(ossProperties);

        // 2. 将连接参数交给 SDK；超时沿用毫秒单位和精确 int 转换，溢出直接失败。
        ClientBuilderConfiguration ossClientConfiguration = new ClientBuilderConfiguration();
        // SDK 的默认协议是 HTTP；即使未来调用方式变化，也强制使用 TLS。
        ossClientConfiguration.setProtocol(Protocol.HTTPS);
        ossClientConfiguration.setConnectionTimeout(
                Math.toIntExact(ossProperties.connectTimeout().toMillis()));
        ossClientConfiguration.setSocketTimeout(
                Math.toIntExact(ossProperties.socketTimeout().toMillis()));
        ossClientConfiguration.setMaxConnections(ossProperties.maxConnections());
        // 3. 客户端由容器管理，销毁时调用 shutdown；这里不访问 Bucket 或上传对象。
        return new OSSClientBuilder()
                .build(
                        endpoint,
                        ossProperties.accessKeyId(),
                        ossProperties.accessKeySecret(),
                        ossClientConfiguration);
    }

    @Bean
    @ConditionalOnProperty(prefix = "portfolio.media", name = "storage", havingValue = "oss")
    ObjectStorage ossObjectStorage(OSS ossClient, MediaStorageProperties mediaStorageProperties) {
        return new AliyunOssObjectStorage(
                ossClient,
                mediaStorageProperties.oss().bucketName(),
                mediaStorageProperties.signedUrlTtl());
    }

    private String validateOssConfiguration(MediaStorageProperties.Oss ossProperties) {
        if (isBlank(ossProperties.endpoint())
                || isBlank(ossProperties.bucketName())
                || isBlank(ossProperties.accessKeyId())
                || isBlank(ossProperties.accessKeySecret())) {
            throw new IllegalStateException(StorageMessageConstants.OSS_CONFIGURATION_INCOMPLETE);
        }
        return validateHttpsEndpoint(ossProperties.endpoint());
    }

    private String validateHttpsEndpoint(String configuredEndpoint) {
        String endpoint = configuredEndpoint.trim();
        try {
            URI endpointUri = new URI(endpoint);
            boolean rootPath =
                    endpointUri.getPath() == null
                            || endpointUri.getPath().isEmpty()
                            || endpointUri.getPath().equals("/");
            if (!"https".equalsIgnoreCase(endpointUri.getScheme())
                    || isBlank(endpointUri.getHost())
                    || endpointUri.getUserInfo() != null
                    || endpointUri.getQuery() != null
                    || endpointUri.getFragment() != null
                    || !rootPath) {
                throw new IllegalStateException(
                        StorageMessageConstants.OSS_ENDPOINT_HTTPS_REQUIRED);
            }
            return endpoint;
        } catch (URISyntaxException error) {
            throw new IllegalStateException(
                    StorageMessageConstants.OSS_ENDPOINT_HTTPS_REQUIRED, error);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
