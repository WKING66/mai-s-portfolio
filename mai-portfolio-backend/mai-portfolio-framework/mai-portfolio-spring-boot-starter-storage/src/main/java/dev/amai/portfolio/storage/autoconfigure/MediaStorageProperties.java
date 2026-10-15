package dev.amai.portfolio.storage.autoconfigure;

import dev.amai.portfolio.storage.MediaStorageType;
import dev.amai.portfolio.storage.StorageMessageConstants;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服务端对象存储配置；通过 Spring 配置绑定，凭据只用于构建 OSS 客户端。 */
@ConfigurationProperties(prefix = "portfolio.media")
public record MediaStorageProperties(
        MediaStorageType storage,

        Path localDirectory,

        Oss oss,

        Duration signedUrlTtl

    ) {
    public static final Duration DEFAULT_SIGNED_URL_TTL = Duration.ofHours(1);

    private static final Path DEFAULT_LOCAL_DIRECTORY = Path.of(".local-media");

    public MediaStorageProperties {
        storage = storage == null ? MediaStorageType.LOCAL : storage;
        localDirectory = localDirectory == null ? DEFAULT_LOCAL_DIRECTORY : localDirectory;
        oss = oss == null ? new Oss(null, null, null, null, null, null, 0) : oss;
        signedUrlTtl = signedUrlTtl == null ? DEFAULT_SIGNED_URL_TTL : signedUrlTtl;
        if (signedUrlTtl.isZero() || signedUrlTtl.isNegative()) {
            throw new IllegalArgumentException(StorageMessageConstants.ACCESS_URL_TTL_INVALID);
        }
    }

    /** OSS 客户端连接参数；超时值为 null 或非正数时使用安全默认值。 */
    public record Oss(
            String endpoint,

            String bucketName,

            String accessKeyId,

            String accessKeySecret,

            Duration connectTimeout,

            Duration socketTimeout,

            int maxConnections

        ) {
        private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

        private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);

        private static final int DEFAULT_MAX_CONNECTIONS = 20;

        public Oss {
            connectTimeout = validDuration(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
            socketTimeout = validDuration(socketTimeout, DEFAULT_SOCKET_TIMEOUT);
            maxConnections = maxConnections > 0 ? maxConnections : DEFAULT_MAX_CONNECTIONS;
        }

        private static Duration validDuration(
                Duration configuredDuration, Duration fallbackDuration) {
            return configuredDuration == null
                            || configuredDuration.isZero()
                            || configuredDuration.isNegative()
                    ? fallbackDuration
                    : configuredDuration;
        }
    }
}
