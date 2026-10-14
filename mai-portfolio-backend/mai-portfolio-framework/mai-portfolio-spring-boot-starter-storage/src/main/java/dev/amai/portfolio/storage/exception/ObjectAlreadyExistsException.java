package dev.amai.portfolio.storage.exception;

import dev.amai.portfolio.storage.StorageMessageConstants;

/** 不可变对象键发生冲突。 */
public final class ObjectAlreadyExistsException extends ObjectStorageException {
    public ObjectAlreadyExistsException(String objectKey) {
        super(StorageMessageConstants.OBJECT_ALREADY_EXISTS + ": " + objectKey);
    }

    public ObjectAlreadyExistsException(String objectKey, Throwable cause) {
        super(StorageMessageConstants.OBJECT_ALREADY_EXISTS + ": " + objectKey, cause);
    }
}
