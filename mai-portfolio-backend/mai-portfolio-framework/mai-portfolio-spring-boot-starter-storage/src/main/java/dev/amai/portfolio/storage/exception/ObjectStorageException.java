package dev.amai.portfolio.storage.exception;

/** 对象存储基础设施失败；边界提示与底层 cause 分开，便于服务端定位原始错误。 */
public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
