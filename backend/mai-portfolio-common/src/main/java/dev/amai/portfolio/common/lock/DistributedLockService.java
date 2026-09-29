package dev.amai.portfolio.common.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 跨进程互斥执行的通用抽象。
 *
 * <p>业务模块只依赖该接口，具体锁实现由基础设施 Starter 提供。</p>
 */
public interface DistributedLockService {
    /**
     * 获取锁后执行操作；等待超时或线程被中断时抛出异常。
     *
     * @param lockName 业务锁名称，不应包含敏感数据
     * @param waitTime 最长等待时间
     * @param leaseTime 锁自动释放时间，必须覆盖操作的正常执行时长
     * @param operation 获锁后执行的操作
     * @return 操作结果
     * @param <T> 结果类型
     */
    <T> T execute(String lockName, Duration waitTime, Duration leaseTime, Supplier<T> operation);
}
