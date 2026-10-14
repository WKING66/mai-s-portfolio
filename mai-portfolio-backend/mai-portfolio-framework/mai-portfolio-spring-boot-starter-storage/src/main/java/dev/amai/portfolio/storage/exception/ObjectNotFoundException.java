package dev.amai.portfolio.storage.exception;

import dev.amai.portfolio.storage.StorageMessageConstants;

/** 存储侧不存在目标对象。 */
public final class ObjectNotFoundException extends ObjectStorageException {
    public ObjectNotFoundException(String objectKey) {
        super(StorageMessageConstants.OBJECT_NOT_FOUND + ": " + objectKey);
    }

    public ObjectNotFoundException(String objectKey, Throwable cause) {
        super(StorageMessageConstants.OBJECT_NOT_FOUND + ": " + objectKey, cause);
    }
}
