package dev.amai.portfolio.storage.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.comm.Protocol;
import dev.amai.portfolio.storage.exception.ObjectAlreadyExistsException;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实私有 OSS 验收测试。
 *
 * <p>仅在显式设置 RUN_OSS_INTEGRATION_TEST=true 时执行，且只操作随机测试对象。</p>
 */
@EnabledIfEnvironmentVariable(named = "RUN_OSS_INTEGRATION_TEST", matches = "(?i)true")
class AliyunOssObjectStorageIntegrationTest {
    private OSS client;
    private AliyunOssObjectStorage storage;
    private String bucketName;

    @BeforeEach
    void setUp() {
        String endpoint = requiredEnvironment("OSS_ENDPOINT");
        bucketName = requiredEnvironment("OSS_BUCKET_NAME");
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setProtocol(Protocol.HTTPS);
        client = new OSSClientBuilder().build(
            endpoint,
            requiredEnvironment("OSS_ACCESS_KEY_ID"),
            requiredEnvironment("OSS_ACCESS_KEY_SECRET"),
            configuration);
        storage = new AliyunOssObjectStorage(
                client, bucketName, java.time.Duration.ofHours(1), requiredEnvironment("OSS_KEY_PREFIX"));
    }

    @AfterEach
    void shutDownClient() {
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    void storesReadsRefusesOverwriteAndDeletesObjectInPrivateBucket() throws Exception {
        String objectKey = "integration-tests/" + UUID.randomUUID() + ".txt";
        byte[] content = "mai-portfolio-oss-integration".getBytes(StandardCharsets.UTF_8);
        try {
            var stored = storage.store(new ObjectStorageWriteRequest(
                objectKey, new ByteArrayInputStream(content), content.length, "text/plain"));

            assertThat(stored.eTag()).isNotBlank();
            assertThat(storage.exists(objectKey)).isTrue();
            var signed = java.net.URI.create(storage.accessUrl(objectKey).url());
            try (var http = java.net.http.HttpClient.newHttpClient()) {
                var request = java.net.http.HttpRequest.newBuilder(signed)
                        .timeout(java.time.Duration.ofSeconds(15)).GET().build();
                var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo(content);
                var unsigned = new java.net.URI(signed.getScheme(), signed.getAuthority(), signed.getPath(), null, null);
                var unsignedRequest = java.net.http.HttpRequest.newBuilder(unsigned)
                        .timeout(java.time.Duration.ofSeconds(15)).GET().build();
                assertThat(http.send(unsignedRequest, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode())
                        .isEqualTo(403);
            }
            try (var object = storage.open(objectKey)) {
                assertThat(object.byteSize()).isEqualTo(content.length);
                assertThat(object.inputStream().readAllBytes()).isEqualTo(content);
            }
            assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
                objectKey, new ByteArrayInputStream(content), content.length, "text/plain")))
                .isInstanceOf(ObjectAlreadyExistsException.class);
        } finally {
            storage.delete(objectKey);
        }
        assertThat(storage.exists(objectKey)).isFalse();
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required integration-test environment: " + name);
        }
        return value;
    }
}
