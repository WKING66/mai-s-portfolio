package dev.amai.portfolio.storage;

import java.io.InputStream;
import java.util.Objects;

/** 单次写入的元数据与调用方持有的输入流；声明长度须与实际内容一致，存储实现负责核对。 */
public record ObjectStorageWriteRequest(
        String objectKey, InputStream inputStream, long byteSize, String contentType) {
    public ObjectStorageWriteRequest {
        objectKey = ObjectStorageKeyValidator.validate(objectKey);
        Objects.requireNonNull(inputStream, StorageMessageConstants.CONTENT_REQUIRED);
        if (byteSize < 0) {
            throw new IllegalArgumentException(StorageMessageConstants.CONTENT_LENGTH_INVALID);
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException(StorageMessageConstants.CONTENT_TYPE_REQUIRED);
        }
        contentType = contentType.trim();
    }
}
