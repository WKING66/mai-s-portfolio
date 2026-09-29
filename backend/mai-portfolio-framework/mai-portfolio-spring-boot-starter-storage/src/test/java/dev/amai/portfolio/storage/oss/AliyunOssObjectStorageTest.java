package dev.amai.portfolio.storage.oss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import dev.amai.portfolio.storage.exception.ObjectAlreadyExistsException;
import dev.amai.portfolio.storage.exception.ObjectNotFoundException;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AliyunOssObjectStorageTest {
    private static final String BUCKET = "portfolio-private";

    @Test
    void uploadUsesPrivateBucketAndForbidsOverwrite() {
        OSS client = mock(OSS.class);
        PutObjectResult result = new PutObjectResult();
        result.setETag("etag-1");
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(result);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(client, BUCKET);
        byte[] content = {1, 2, 3};

        var stored = storage.store(new ObjectStorageWriteRequest(
            "images/object.bin", new ByteArrayInputStream(content), content.length,
            "application/octet-stream"));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertThat(request.getValue().getBucketName()).isEqualTo(BUCKET);
        assertThat(request.getValue().getKey()).isEqualTo("images/object.bin");
        ObjectMetadata metadata = request.getValue().getMetadata();
        assertThat(metadata.getContentLength()).isEqualTo(content.length);
        assertThat(metadata.getContentType()).isEqualTo("application/octet-stream");
        assertThat(metadata.getRawMetadata().get("x-oss-forbid-overwrite")).isEqualTo("true");
        assertThat(stored.eTag()).isEqualTo("etag-1");
    }

    @Test
    void mapsOssFileAlreadyExistsToStableStorageException() {
        OSS client = mock(OSS.class);
        OSSException conflict = mock(OSSException.class);
        when(conflict.getErrorCode()).thenReturn("FileAlreadyExists");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(conflict);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(client, BUCKET);
        byte[] content = {1};

        assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
            "images/object.bin", new ByteArrayInputStream(content), content.length,
            "application/octet-stream")))
            .isInstanceOf(ObjectAlreadyExistsException.class);
    }

    @Test
    void readsChecksAndDeletesObjectThroughPrivateBucket() throws Exception {
        OSS client = mock(OSS.class);
        byte[] content = {4, 5, 6};
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        OSSObject object = new OSSObject();
        object.setObjectMetadata(metadata);
        object.setObjectContent(new ByteArrayInputStream(content));
        when(client.getObject(BUCKET, "images/object.bin")).thenReturn(object);
        when(client.doesObjectExist(BUCKET, "images/object.bin")).thenReturn(true);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(client, BUCKET);

        assertThat(storage.exists("images/object.bin")).isTrue();
        try (var stored = storage.open("images/object.bin")) {
            assertThat(stored.byteSize()).isEqualTo(content.length);
            assertThat(stored.inputStream().readAllBytes()).isEqualTo(content);
        }
        storage.delete("images/object.bin");

        verify(client).deleteObject(BUCKET, "images/object.bin");
    }

    @Test
    void mapsMissingOssObjectToStableStorageException() {
        OSS client = mock(OSS.class);
        OSSException missing = mock(OSSException.class);
        when(missing.getErrorCode()).thenReturn("NoSuchKey");
        when(client.getObject(BUCKET, "missing/object.bin")).thenThrow(missing);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(client, BUCKET);

        assertThatThrownBy(() -> storage.open("missing/object.bin"))
            .isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void rejectsContentShorterThanDeclaredLengthBeforeCallingOss() {
        OSS client = mock(OSS.class);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(client, BUCKET);

        assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
            "images/short.bin", new ByteArrayInputStream(new byte[] {1, 2}), 3,
            "application/octet-stream")))
            .isInstanceOf(ObjectStorageException.class)
            .hasMessageContaining("实际对象字节数与声明值不一致");
        verifyNoInteractions(client);
    }

    @Test
    void rejectsContentLongerThanDeclaredLengthBeforeCallingOss() {
        OSS client = mock(OSS.class);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(client, BUCKET);

        assertThatThrownBy(() -> storage.store(new ObjectStorageWriteRequest(
            "images/long.bin", new ByteArrayInputStream(new byte[] {1, 2, 3}), 2,
            "application/octet-stream")))
            .isInstanceOf(ObjectStorageException.class)
            .hasMessageContaining("实际对象字节数与声明值不一致");
        verifyNoInteractions(client);
    }
}
