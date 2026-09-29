package dev.amai.portfolio.storage.autoconfigure;

import dev.amai.portfolio.storage.MediaStorageType;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 对象存储配置；AccessKey 仅从服务端环境变量绑定。 */
@ConfigurationProperties(prefix = "portfolio.media")
public record MediaStorageProperties(MediaStorageType storage, Path localDirectory,
                                     Oss oss) {
    private static final Path DEFAULT_LOCAL_DIRECTORY = Path.of(".local-media");

    public MediaStorageProperties {
        storage = storage == null ? MediaStorageType.LOCAL : storage;
        localDirectory = localDirectory == null ? DEFAULT_LOCAL_DIRECTORY : localDirectory;
        oss = oss == null ? new Oss(null, null, null, null, null, null, 0) : oss;
    }

    /** OSS 客户端连接参数；超时值为 null 或非正数时使用安全默认值。 */
    public record Oss(String endpoint, String bucketName, String accessKeyId,
                      String accessKeySecret, Duration connectTimeout,
                      Duration socketTimeout, int maxConnections) {
        private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
        private static final Duration DEFAULT_SOCKET_TIMEOUT = Duration.ofSeconds(30);
        private static final int DEFAULT_MAX_CONNECTIONS = 20;

        public Oss {
            connectTimeout = validDuration(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
            socketTimeout = validDuration(socketTimeout, DEFAULT_SOCKET_TIMEOUT);
            maxConnections = maxConnections > 0 ? maxConnections : DEFAULT_MAX_CONNECTIONS;
        }

        private static Duration validDuration(Duration configured, Duration fallback) {
            return configured == null || configured.isZero() || configured.isNegative()
                ? fallback : configured;
        }
    }
}
