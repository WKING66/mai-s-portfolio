package dev.amai.portfolio.storage.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import dev.amai.portfolio.storage.StorageMessageConstants;
import dev.amai.portfolio.storage.exception.ObjectAlreadyExistsException;
import dev.amai.portfolio.storage.exception.ObjectNotFoundException;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import dev.amai.portfolio.storage.ObjectStorageKeyValidator;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import dev.amai.portfolio.storage.StoredObject;
import dev.amai.portfolio.storage.StoredObjectContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 阿里云私有 OSS 实现，不生成公开 URL，也不把凭据传给调用方。 */
public final class AliyunOssObjectStorage implements ObjectStorage {
    private static final System.Logger LOG = System.getLogger(AliyunOssObjectStorage.class.getName());
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final String FORBID_OVERWRITE_HEADER = "x-oss-forbid-overwrite";
    private static final String FILE_ALREADY_EXISTS = "FileAlreadyExists";
    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final OSS client;
    private final String bucketName;

    public AliyunOssObjectStorage(OSS client, String bucketName) {
        this.client = Objects.requireNonNull(client);
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException(StorageMessageConstants.OSS_CONFIGURATION_INCOMPLETE);
        }
        this.bucketName = bucketName.trim();
    }

    @Override
    public StoredObject store(ObjectStorageWriteRequest request) {
        Objects.requireNonNull(request, StorageMessageConstants.CONTENT_REQUIRED);
        Path stagedContent = stageAndVerify(request);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(request.byteSize());
        metadata.setContentType(request.contentType());
        // OSS 默认覆盖同名对象，必须显式带条件写入头保障 READY 对象不可变。
        metadata.setHeader(FORBID_OVERWRITE_HEADER, Boolean.TRUE.toString());
        try (InputStream input = Files.newInputStream(stagedContent)) {
            PutObjectRequest putRequest = new PutObjectRequest(
                bucketName, request.objectKey(), input, metadata);
            PutObjectResult result = client.putObject(putRequest);
            return new StoredObject(request.objectKey(), request.byteSize(), result.getETag());
        } catch (OSSException error) {
            throw translate(error, request.objectKey(), StorageMessageConstants.WRITE_FAILED);
        } catch (ClientException error) {
            throw new ObjectStorageException(StorageMessageConstants.WRITE_FAILED, error);
        } catch (IOException error) {
            throw new ObjectStorageException(StorageMessageConstants.WRITE_FAILED, error);
        } finally {
            deleteStagedContent(stagedContent);
        }
    }

    @Override
    public StoredObjectContent open(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        try {
            OSSObject object = client.getObject(bucketName, key);
            return new StoredObjectContent(
                object.getObjectContent(), object.getObjectMetadata().getContentLength());
        } catch (OSSException error) {
            throw translate(error, key, StorageMessageConstants.READ_FAILED);
        } catch (ClientException error) {
            throw new ObjectStorageException(StorageMessageConstants.READ_FAILED, error);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        try {
            return client.doesObjectExist(bucketName, key);
        } catch (OSSException | ClientException error) {
            throw new ObjectStorageException(StorageMessageConstants.READ_FAILED, error);
        }
    }

    @Override
    public void delete(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        try {
            client.deleteObject(bucketName, key);
        } catch (OSSException | ClientException error) {
            throw new ObjectStorageException(StorageMessageConstants.DELETE_FAILED, error);
        }
    }

    private ObjectStorageException translate(OSSException error, String objectKey,
                                             String fallbackMessage) {
        if (FILE_ALREADY_EXISTS.equals(error.getErrorCode())) {
            return new ObjectAlreadyExistsException(objectKey, error);
        }
        if (NO_SUCH_KEY.equals(error.getErrorCode())) {
            return new ObjectNotFoundException(objectKey, error);
        }
        return new ObjectStorageException(fallbackMessage, error);
    }

    /**
     * 先把输入流完整写入临时文件并核对长度，避免 OSS 按错误长度截断对象或等待不存在的字节。
     */
    private Path stageAndVerify(ObjectStorageWriteRequest request) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("mai-portfolio-oss-", ".upload");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                copyExactly(request.inputStream(), output, request.byteSize());
            }
            return temporary;
        } catch (IOException error) {
            deleteStagedContent(temporary);
            throw new ObjectStorageException(StorageMessageConstants.WRITE_FAILED, error);
        } catch (ObjectStorageException error) {
            deleteStagedContent(temporary);
            throw error;
        }
    }

    private void copyExactly(InputStream input, OutputStream output, long expectedSize)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long copied = 0;
        while (copied < expectedSize) {
            int wanted = (int) Math.min(buffer.length, expectedSize - copied);
            int read = input.read(buffer, 0, wanted);
            if (read < 0) {
                throw lengthMismatch();
            }
            output.write(buffer, 0, read);
            copied += read;
        }
        if (input.read() != -1) {
            throw lengthMismatch();
        }
    }

    private ObjectStorageException lengthMismatch() {
        return new ObjectStorageException(StorageMessageConstants.CONTENT_LENGTH_MISMATCH);
    }

    private void deleteStagedContent(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            LOG.log(System.Logger.Level.WARNING,
                "Failed to delete temporary OSS upload file: {0}", path.getFileName());
        }
    }
}
