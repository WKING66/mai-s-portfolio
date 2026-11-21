package dev.amai.portfolio.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.enums.AgentTurnState;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTurnMapper extends BaseMapper<AgentTurn> {
    AgentTurn byExecution(@Param("id") Long executionId, @Param("owner") String owner);

    default AgentTurn byRequestId(String requestId) {
        return selectOne(Wrappers.lambdaQuery(AgentTurn.class).eq(AgentTurn::getRequestId, requestId));
    }

    int insertPending(@Param("turn") AgentTurn turn);

    // 访客与账号使用不同锁命名空间，认领固定按访客、账号顺序取得两把锁。
    @Select("SELECT clock_timestamp() FROM pg_advisory_xact_lock("
            + "CASE WHEN starts_with(#{owner}, 'visitor:') THEN 713742601 ELSE 713742602 END, hashtext(#{owner}))")
    OffsetDateTime lockOwner(@Param("owner") String owner);

    default List<AgentTurn> quotaHistorySince(OffsetDateTime cutoff) {
        return selectList(Wrappers.lambdaQuery(AgentTurn.class)
                .select(AgentTurn::getRequestId, AgentTurn::getQuotaAt)
                .gt(AgentTurn::getQuotaAt, cutoff));
    }

    long countOwnerQuotaSince(@Param("owner") String owner, @Param("cutoff") OffsetDateTime cutoff);

    default List<AgentTurn> turns(Long conversationId, int limit) {
        return selectList(new Page<>(1, limit, false), Wrappers.lambdaQuery(AgentTurn.class)
                .eq(AgentTurn::getConversationId, conversationId)
                .eq(AgentTurn::isDeleted, false)
                .orderByDesc(AgentTurn::getId));
    }

    default List<AgentTurn> recentCompleted(Long conversationId, int limit) {
        return selectList(new Page<>(1, limit, false), Wrappers.lambdaQuery(AgentTurn.class)
                .eq(AgentTurn::getConversationId, conversationId)
                .eq(AgentTurn::isDeleted, false)
                .eq(AgentTurn::getStatus, AgentTurnState.COMPLETED.getCode())
                .orderByDesc(AgentTurn::getId));
    }

    default boolean active(Long conversationId) {
        return exists(Wrappers.lambdaQuery(AgentTurn.class)
                .eq(AgentTurn::getConversationId, conversationId)
                .in(AgentTurn::getStatus, AgentTurnState.activeCodes()));
    }

    default int clear(Long conversationId) {
        // 只移出用户可读内容，配额查询仍需统计已受理的轮次。
        return update(Wrappers.lambdaUpdate(AgentTurn.class)
                .set(AgentTurn::isDeleted, true)
                .eq(AgentTurn::getConversationId, conversationId)
                .eq(AgentTurn::isDeleted, false));
    }

    default int attach(String requestId, Long executionId) {
        return update(Wrappers.lambdaUpdate(AgentTurn.class)
                .set(AgentTurn::getExecutionId, executionId)
                .set(AgentTurn::getStatus, AgentTurnState.ACCEPTED.getCode())
                .eq(AgentTurn::getRequestId, requestId)
                .isNull(AgentTurn::getExecutionId)
                .eq(AgentTurn::getStatus, AgentTurnState.PENDING.getCode()));
    }

    default int failUnbound(String requestId, String error) {
        return update(Wrappers.lambdaUpdate(AgentTurn.class)
                .set(AgentTurn::getStatus, AgentTurnState.FAILED.getCode())
                .set(AgentTurn::getErrorMes, error)
                .set(AgentTurn::getUsageStatus, 3)
                .eq(AgentTurn::getRequestId, requestId)
                .isNull(AgentTurn::getExecutionId)
                .eq(AgentTurn::getStatus, AgentTurnState.PENDING.getCode()));
    }

    default int saveTerminal(String requestId, Long executionId, String answer, AgentTurnState state,
            String errorMes, Long inputTokens, Long outputTokens, int usageStatus) {
        return update(Wrappers.lambdaUpdate(AgentTurn.class)
                .set(AgentTurn::getAnswer, answer)
                .set(AgentTurn::getStatus, state.getCode())
                .set(AgentTurn::getErrorMes, errorMes)
                .set(AgentTurn::getInputTokens, inputTokens)
                .set(AgentTurn::getOutputTokens, outputTokens)
                .set(AgentTurn::getUsageStatus, usageStatus)
                .eq(AgentTurn::getRequestId, requestId)
                .eq(AgentTurn::getExecutionId, executionId)
                .in(AgentTurn::getStatus, AgentTurnState.activeCodes()));
    }

    default List<AgentTurn> unfinished(int limit) {
        return selectList(new Page<>(1, limit, false), Wrappers.lambdaQuery(AgentTurn.class)
                .in(AgentTurn::getStatus, AgentTurnState.activeCodes())
                .orderByAsc(AgentTurn::getId));
    }
}
