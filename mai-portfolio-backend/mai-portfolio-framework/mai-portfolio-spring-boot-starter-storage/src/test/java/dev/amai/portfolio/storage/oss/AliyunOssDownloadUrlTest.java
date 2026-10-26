package dev.amai.portfolio.storage.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AliyunOssDownloadUrlTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "report.pdf", "archive.tar.gz", "document.docx", "table.xlsx", "README",
            "说明 文档+v2.pdf", "report \"final\"; 100% *'.zip"
    })
    void signsOriginalFilenameForLegacyBinObjectsWithoutReadingOrChangingThem(String filename)
            throws Exception {
        OSS ossClient = mock(OSS.class);
        var signedUrl = URI.create("https://oss.test/legacy.bin?signature=test").toURL();
        when(ossClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(signedUrl);
        var storage = new AliyunOssObjectStorage(ossClient, "test-bucket", Duration.ofMinutes(10));
        Instant before = Instant.now();

        var access = storage.downloadUrl("assets/attachments/legacy.bin", filename);

        var captured = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(ossClient).generatePresignedUrl(captured.capture());
        var request = captured.getValue();
        assertThat(request.getBucketName()).isEqualTo("test-bucket");
        assertThat(request.getKey()).isEqualTo("assets/attachments/legacy.bin");
        String disposition = request.getResponseHeaders().getContentDisposition();
        assertThat(disposition).startsWith("attachment; filename=\"").contains("; filename*=UTF-8''");
        assertThat(disposition.chars().allMatch(value -> value >= 32 && value < 127)).isTrue();
        String encoded = disposition.substring(disposition.indexOf("filename*=UTF-8''") + 17);
        assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo(filename);
        assertThat(encoded).doesNotContain("+", "*", "'");
        assertThat(access.url()).isEqualTo(signedUrl.toExternalForm());
        assertThat(access.expiresAt()).isEqualTo(request.getExpiration().toInstant());
        assertThat(access.expiresAt()).isBetween(before.plusSeconds(599), Instant.now().plusSeconds(601));
        verifyNoMoreInteractions(ossClient);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../report.pdf", "folder\\report.pdf", "bad\r\nX-Header: injected.pdf", "bad\u0000.pdf"})
    void rejectsInvalidFilenamesBeforeCallingOss(String filename) {
        OSS ossClient = mock(OSS.class);
        var storage = new AliyunOssObjectStorage(ossClient, "test-bucket");

        assertThatThrownBy(() -> storage.downloadUrl("assets/attachments/legacy.bin", filename))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("下载文件名");
        verifyNoInteractions(ossClient);
    }
}
