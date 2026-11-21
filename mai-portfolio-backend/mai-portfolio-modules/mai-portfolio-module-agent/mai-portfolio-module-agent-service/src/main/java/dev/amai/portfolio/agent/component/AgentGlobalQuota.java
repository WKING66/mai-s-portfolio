package dev.amai.portfolio.agent.component;

import dev.amai.portfolio.agent.entity.AgentSiteSettings;
import dev.amai.portfolio.agent.mapper.AgentTurnMapper;
import dev.amai.portfolio.agent.mapper.AgentSiteSettingsMapper;
import dev.amai.portfolio.redis.rate.RedisRateLimiter;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** 管理全站轮次窗口。Redis 控制新轮次放行，正式轮次仍由数据库保存。 */
@Component
public class AgentGlobalQuota {
    private static final Logger log = LoggerFactory.getLogger(AgentGlobalQuota.class);
    private static final String SUBJECT = "site-guide:chat";

    private final AgentTurnMapper agentTurnMapper;
    private final AgentSiteSettingsMapper agentSiteSettingsMapper;
    private final RedisRateLimiter redisRateLimiter;

    public AgentGlobalQuota(AgentTurnMapper agentTurnMapper, AgentSiteSettingsMapper agentSiteSettingsMapper,
            RedisRateLimiter redisRateLimiter) {
        this.agentTurnMapper = agentTurnMapper;
        this.agentSiteSettingsMapper = agentSiteSettingsMapper;
        this.redisRateLimiter = redisRateLimiter;
    }

    /** 单机服务启动时补齐当前窗口，确保切换或丢失 Redis 状态后不会从零放行。 */
    @EventListener(ApplicationReadyEvent.class)
    void restoreAtStartup() {
        try {
            var settings = agentSiteSettingsMapper.read();
            if (settings != null) {
                restore(Duration.ofHours(settings.globalWindowHours()), true);
            }
        } catch (DataAccessException | RedisException error) {
            // 额度恢复失败不阻止网站启动。Redis 缺少当前窗口标记时仍拒绝新轮次。
            log.warn("启动时未能恢复站点对话额度，全站窗口缺失时暂停新轮次受理",
                    error.getClass().getSimpleName());
        }
    }

    public void requirePermit(String requestId, AgentSiteSettings settings) {
        Duration window = Duration.ofHours(settings.globalWindowHours());
        RedisRateLimiter.SlidingWindowResult result;
        try {
            result = redisRateLimiter.acquireSlidingWindow(SUBJECT, requestId, window, settings.globalRequestLimit());
            // 窗口调长后才加载旧记录。合并保留 Redis 内已登记的请求，包括尚未提交的轮次。
            if (result == RedisRateLimiter.SlidingWindowResult.HISTORY_REQUIRED && restore(window, false)) {
                result = redisRateLimiter.acquireSlidingWindow(
                        SUBJECT, requestId, window, settings.globalRequestLimit());
            }
        } catch (RedisException error) {
            throw unavailable();
        }
        switch (result) {
            case ALLOWED -> { }
            case EXHAUSTED -> throw new ApiException(ApiErrorCode.RATE_LIMITED, "站点对话额度暂已用完，请稍后再试。");
            case UNINITIALIZED, HISTORY_REQUIRED -> throw unavailable();
        }
    }

    private boolean restore(Duration window, boolean initialize) {
        var cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(window);
        Map<String, Long> history = new HashMap<>();
        for (var turn : agentTurnMapper.quotaHistorySince(cutoff)) {
            history.put(turn.getRequestId(), turn.getQuotaAt().toInstant().toEpochMilli());
        }
        return redisRateLimiter.restoreSlidingWindow(SUBJECT, window, history, initialize);
    }

    private static ApiException unavailable() {
        return new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "站点对话暂不可用，请稍后重试。");
    }
}
