package dev.amai.portfolio.storage.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.ObjectStorageKeyValidator;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import dev.amai.portfolio.storage.StorageMessageConstants;
import dev.amai.portfolio.storage.StoredObject;
import dev.amai.portfolio.storage.StoredObjectAccessUrl;
import dev.amai.portfolio.storage.StoredObjectContent;
import dev.amai.portfolio.storage.autoconfigure.MediaStorageProperties;
import dev.amai.portfolio.storage.exception.ObjectAlreadyExistsException;
import dev.amai.portfolio.storage.exception.ObjectNotFoundException;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/** 阿里云私有 OSS 实现；返回临时签名地址供浏览器直连，不传输 AccessKey。 */
public final class AliyunOssObjectStorage implements ObjectStorage {
    private static final System.Logger LOG =
            System.getLogger(AliyunOssObjectStorage.class.getName());
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final String FORBID_OVERWRITE_HEADER = "x-oss-forbid-overwrite";
    private static final String FILE_ALREADY_EXISTS = "FileAlreadyExists";
    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final OSS ossClient;
    private final String bucketName;
    private final Duration signedUrlTtl;

    public AliyunOssObjectStorage(OSS ossClient, String bucketName) {
        this(ossClient, bucketName, MediaStorageProperties.DEFAULT_SIGNED_URL_TTL);
    }

    public AliyunOssObjectStorage(OSS ossClient, String bucketName, Duration signedUrlTtl) {
        this.ossClient = Objects.requireNonNull(ossClient);
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException(
                    StorageMessageConstants.OSS_CONFIGURATION_INCOMPLETE);
        }
        this.bucketName = bucketName.trim();
        this.signedUrlTtl = Objects.requireNonNull(signedUrlTtl);
        if (signedUrlTtl.isZero() || signedUrlTtl.isNegative()) {
            throw new IllegalArgumentException(StorageMessageConstants.ACCESS_URL_TTL_INVALID);
        }
    }

    @Override
    public StoredObjectAccessUrl accessUrl(String objectKey) {
        // 1. 校验对象身份并将到期时间对齐到 SDK 使用的毫秒精度。
        String key = ObjectStorageKeyValidator.validate(objectKey);
        Instant expiresAt = Instant.ofEpochMilli(Instant.now().plus(signedUrlTtl).toEpochMilli());
        try {
            // 2. 只生成 HTTPS 签名地址，不查询或下载对象；业务授权由上层完成。
            var signedUrl = ossClient.generatePresignedUrl(bucketName, key, Date.from(expiresAt));
            if (signedUrl == null || !"https".equalsIgnoreCase(signedUrl.getProtocol())) {
                throw new ObjectStorageException(StorageMessageConstants.ACCESS_URL_FAILED);
            }
            return new StoredObjectAccessUrl(signedUrl.toExternalForm(), expiresAt);
        } catch (OSSException | ClientException error) {
            throw new ObjectStorageException(StorageMessageConstants.ACCESS_URL_FAILED, error);
        }
    }

    @Override
    public StoredObject store(ObjectStorageWriteRequest request) {
        Objects.requireNonNull(request, StorageMessageConstants.CONTENT_REQUIRED);
        // 1. 先核对完整字节长度；不匹配时清理暂存内容，不调用 OSS。
        Path stagedContent = stageAndVerify(request);
        // 2. 传递已校验元数据，并使用 OSS 条件写入禁止同键覆盖。
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(request.byteSize());
        objectMetadata.setContentType(request.contentType());
        // OSS 默认覆盖同名对象，必须显式带条件写入头保障 READY 对象不可变。
        objectMetadata.setHeader(FORBID_OVERWRITE_HEADER, Boolean.TRUE.toString());
        try (InputStream input = Files.newInputStream(stagedContent)) {
            // 3. 从暂存文件上传，关闭本次打开的流；原始输入流仍归调用方持有。
            PutObjectRequest putObjectRequest =
                    new PutObjectRequest(bucketName, request.objectKey(), input, objectMetadata);
            PutObjectResult putObjectResult = ossClient.putObject(putObjectRequest);
            return new StoredObject(
                    request.objectKey(), request.byteSize(), putObjectResult.getETag());
        } catch (OSSException error) {
            throw translate(error, request.objectKey(), StorageMessageConstants.WRITE_FAILED);
        } catch (ClientException | IOException error) {
            throw new ObjectStorageException(StorageMessageConstants.WRITE_FAILED, error);
        } finally {
            // 4. 清理本地暂存文件；数据库事务与已上传 OSS 对象的补偿由上层负责。
            deleteStagedContent(stagedContent);
        }
    }

    @Override
    public StoredObjectContent open(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        try {
            // 内容流交给返回对象持有，调用方关闭 StoredObjectContent 时释放连接。
            OSSObject ossObject = ossClient.getObject(bucketName, key);
            return new StoredObjectContent(
                    ossObject.getObjectContent(), ossObject.getObjectMetadata().getContentLength());
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
            return ossClient.doesObjectExist(bucketName, key);
        } catch (OSSException | ClientException error) {
            throw new ObjectStorageException(StorageMessageConstants.READ_FAILED, error);
        }
    }

    @Override
    public void delete(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        try {
            ossClient.deleteObject(bucketName, key);
        } catch (OSSException | ClientException error) {
            throw new ObjectStorageException(StorageMessageConstants.DELETE_FAILED, error);
        }
    }

    private ObjectStorageException translate(
            OSSException error, String objectKey, String fallbackMessage) {
        // 只转换明确的冲突/不存在错误；其他厂商错误保留 cause，返回稳定边界提示。
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
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("mai-portfolio-oss-", ".upload");
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                copyExactly(request.inputStream(), output, request.byteSize());
            }
            return temporaryFile;
        } catch (IOException error) {
            deleteStagedContent(temporaryFile);
            throw new ObjectStorageException(StorageMessageConstants.WRITE_FAILED, error);
        } catch (ObjectStorageException error) {
            deleteStagedContent(temporaryFile);
            throw error;
        }
    }

    /** 按声明长度复制，并额外读取一次确认流结束；短流与长流都不能上传。 */
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
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Failed to delete temporary OSS upload file: {0}",
                    path.getFileName());
        }
    }
}
