package dev.amai.portfolio.common.lock;

/** 无法在约定时间内取得分布式锁时抛出的基础设施异常。 */
public class LockAcquisitionException extends RuntimeException {
    public LockAcquisitionException(String message) {
        super(message);
    }

    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
