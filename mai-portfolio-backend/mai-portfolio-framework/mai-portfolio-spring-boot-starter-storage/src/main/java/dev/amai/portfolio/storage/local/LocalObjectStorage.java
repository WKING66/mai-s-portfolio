package dev.amai.portfolio.storage.local;

import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.ObjectStorageKeyValidator;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import dev.amai.portfolio.storage.StorageMessageConstants;
import dev.amai.portfolio.storage.StoredObject;
import dev.amai.portfolio.storage.StoredObjectAccessUrl;
import dev.amai.portfolio.storage.StoredObjectContent;
import dev.amai.portfolio.storage.exception.ObjectAlreadyExistsException;
import dev.amai.portfolio.storage.exception.ObjectNotFoundException;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 开发环境本地对象存储，与 OSS 实现保持相同的不可覆盖语义。 */
public final class LocalObjectStorage implements ObjectStorage {
    private static final Logger LOG = LoggerFactory.getLogger(LocalObjectStorage.class);

    private final Path rootDirectory;

    public LocalObjectStorage(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException error) {
            throw new ObjectStorageException(
                    StorageMessageConstants.DIRECTORY_INITIALIZATION_FAILED, error);
        }
    }

    @Override
    public StoredObject store(ObjectStorageWriteRequest request) {
        Objects.requireNonNull(request, StorageMessageConstants.CONTENT_REQUIRED);
        // 1. 校验键并定位目标，在目标目录创建临时文件，完整写完后才发布文件。
        Path targetPath = resolve(request.objectKey());
        Path parentDirectory = targetPath.getParent();
        Path temporaryFile = null;
        try {
            Files.createDirectories(parentDirectory);
            temporaryFile = Files.createTempFile(parentDirectory, ".upload-", ".tmp");
            // 2. 输入流由调用方关闭；这里只关闭输出流并核对实际写入长度。
            long actualSize;
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                actualSize = request.inputStream().transferTo(output);
            }
            if (actualSize != request.byteSize()) {
                throw new ObjectStorageException(StorageMessageConstants.CONTENT_LENGTH_MISMATCH);
            }
            // 3. 不使用 REPLACE_EXISTING：版本化资源一旦就绪，不允许被同键字节静默替换。
            Files.move(temporaryFile, targetPath);
            temporaryFile = null;
            return new StoredObject(request.objectKey(), actualSize, null);
        } catch (FileAlreadyExistsException error) {
            throw new ObjectAlreadyExistsException(request.objectKey(), error);
        } catch (ObjectStorageException error) {
            throw error;
        } catch (IOException error) {
            throw new ObjectStorageException(StorageMessageConstants.WRITE_FAILED, error);
        } finally {
            // 4. 只清理本次未发布的临时文件，保留原始写入异常优先级。
            deleteTemporaryFile(temporaryFile);
        }
    }

    @Override
    public StoredObjectAccessUrl accessUrl(String objectKey) {
        ObjectStorageKeyValidator.validate(objectKey);
        throw new ObjectStorageException(StorageMessageConstants.LOCAL_DIRECT_ACCESS_UNAVAILABLE);
    }

    @Override
    public StoredObjectAccessUrl downloadUrl(String objectKey, String filename) {
        ObjectStorageKeyValidator.validate(objectKey);
        throw new ObjectStorageException(StorageMessageConstants.LOCAL_DIRECT_ACCESS_UNAVAILABLE);
    }

    @Override
    public StoredObjectContent open(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        Path objectPath = resolve(key);
        try {
            // 先取长度再打开流，避免第二个参数求值失败时遗失已打开的文件句柄。
            long byteSize = Files.size(objectPath);
            return new StoredObjectContent(Files.newInputStream(objectPath), byteSize);
        } catch (NoSuchFileException error) {
            throw new ObjectNotFoundException(key, error);
        } catch (IOException error) {
            throw new ObjectStorageException(StorageMessageConstants.READ_FAILED, error);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    @Override
    public void delete(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException error) {
            throw new ObjectStorageException(StorageMessageConstants.DELETE_FAILED, error);
        }
    }

    private Path resolve(String objectKey) {
        String key = ObjectStorageKeyValidator.validate(objectKey);
        Path resolved = rootDirectory.resolve(key).normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_INVALID);
        }
        return resolved;
    }

    private void deleteTemporaryFile(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException error) {
            // 原始写入异常优先，但必须留下可观测信号，避免敏感临时文件静默堆积。
            LOG.warn(
                    "Failed to delete local object storage temporary file, fileName={}",
                    temporaryFile.getFileName(),
                    error);
        }
    }
}
