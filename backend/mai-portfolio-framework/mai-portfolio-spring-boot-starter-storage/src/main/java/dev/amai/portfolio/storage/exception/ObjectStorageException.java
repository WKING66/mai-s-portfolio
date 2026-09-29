package dev.amai.portfolio.storage.exception;

/** 对象存储基础设施失败，不向上层泄露本地路径或云厂商响应细节。 */
public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
