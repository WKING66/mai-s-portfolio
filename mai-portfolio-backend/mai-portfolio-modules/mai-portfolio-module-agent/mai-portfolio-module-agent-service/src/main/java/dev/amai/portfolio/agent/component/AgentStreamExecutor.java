package dev.amai.portfolio.agent.component;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.BUSY_RETRY_AFTER_SECONDS;

import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.WebAsyncUtils;

@Component
public class AgentStreamExecutor {
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore newStreams;

    public AgentStreamExecutor(@Value("${site-guide.chat-streams.max-new:20}") int maxNewStreams,
            @Value("${site-guide.chat-streams.resume-reserve:2}") int resumeReserve) {
        if (maxNewStreams < 1 || resumeReserve < 1) {
            throw new IllegalArgumentException("Chat stream limits must be positive.");
        }
        newStreams = new Semaphore(maxNewStreams);
        int maxStreams = Math.addExact(maxNewStreams, resumeReserve);
        // 聊天组件持有执行器，Spring Boot 仍为导出等接口配置原来的 MVC 执行器。
        executor = new ThreadPoolTaskExecutor() {
            @Override
            public Future<?> submit(Runnable task) {
                try {
                    return super.submit(task);
                } catch (TaskRejectedException error) {
                    if (getThreadPoolExecutor().isShutdown()) {
                        throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "对话服务暂不可用，请重试原请求。");
                    }
                    throw busy();
                }
            }
        };
        executor.setThreadNamePrefix("agent-sse-");
        executor.setCorePoolSize(maxStreams);
        executor.setMaxPoolSize(maxStreams);
        executor.setQueueCapacity(0);
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
    }

    public void select(HttpServletRequest request) {
        WebAsyncUtils.getAsyncManager(request).setTaskExecutor(executor);
    }

    public void runSubmission(Runnable stream) {
        // 在记额度前拒绝满载的新提交，给已有执行的续订保留线程。
        if (!newStreams.tryAcquire()) {
            throw busy();
        }
        try {
            stream.run();
        } finally {
            newStreams.release();
        }
    }

    @PreDestroy
    void close() {
        executor.shutdown();
    }

    private static ApiException busy() {
        return new ApiException(ApiErrorCode.CHAT_STREAM_BUSY, "对话连接繁忙，请稍后重试本轮。",
                BUSY_RETRY_AFTER_SECONDS);
    }
}
