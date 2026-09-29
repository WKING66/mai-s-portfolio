package dev.amai.portfolio.storage;

import java.io.InputStream;
import java.util.Objects;

/** 单次对象写入所需的稳定元数据与字节流。 */
public record ObjectStorageWriteRequest(String objectKey, InputStream inputStream,
                                        long byteSize, String contentType) {
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
