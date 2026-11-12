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
    AgentTurn byExecution(@Param("id") String executionId, @Param("owner") String owner);

    boolean isOwnedReplay(@Param("requestId") String requestId, @Param("conversationId") String conversationId,
            @Param("owner") String owner, @Param("question") String question);

    int insertPending(@Param("turn") AgentTurn turn);

    boolean globalQuotaReached(@Param("hours") int windowHours, @Param("limit") int limit);

    @Select("SELECT clock_timestamp() FROM pg_advisory_xact_lock(#{key})")
    OffsetDateTime lockQuota(@Param("key") long key);

    default long countQuotaSince(OffsetDateTime cutoff) {
        return selectCount(Wrappers.lambdaQuery(AgentTurn.class)
                .gt(AgentTurn::getQuotaAt, cutoff));
    }

    long countOwnerQuotaSince(@Param("owner") String owner, @Param("cutoff") OffsetDateTime cutoff);

    default List<AgentTurn> turns(String conversationId, int limit) {
        return selectList(new Page<>(1, limit, false), Wrappers.lambdaQuery(AgentTurn.class)
                .eq(AgentTurn::getConversationId, conversationId)
                .orderByDesc(AgentTurn::getOrdinal));
    }

    default List<AgentTurn> recentCompleted(String conversationId, int limit) {
        return selectList(new Page<>(1, limit, false), Wrappers.lambdaQuery(AgentTurn.class)
                .eq(AgentTurn::getConversationId, conversationId)
                .eq(AgentTurn::getStatus, AgentTurnState.COMPLETED.getCode())
                .orderByDesc(AgentTurn::getOrdinal));
    }

    default boolean active(String conversationId) {
        return exists(Wrappers.lambdaQuery(AgentTurn.class)
                .eq(AgentTurn::getConversationId, conversationId)
                .in(AgentTurn::getStatus, AgentTurnState.activeCodes()));
    }

    default int attach(String requestId, String executionId) {
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
                .set(AgentTurn::getErrorCode, error)
                .eq(AgentTurn::getRequestId, requestId)
                .isNull(AgentTurn::getExecutionId)
                .eq(AgentTurn::getStatus, AgentTurnState.PENDING.getCode()));
    }

    default int saveTerminal(String requestId, String executionId, String answer, AgentTurnState state, String error) {
        return update(Wrappers.lambdaUpdate(AgentTurn.class)
                .set(AgentTurn::getAnswer, answer)
                .set(AgentTurn::getStatus, state.getCode())
                .set(AgentTurn::getErrorCode, error)
                .eq(AgentTurn::getRequestId, requestId)
                .eq(AgentTurn::getExecutionId, executionId)
                .in(AgentTurn::getStatus, AgentTurnState.activeCodes()));
    }

    default List<AgentTurn> unfinished(int limit) {
        return selectList(new Page<>(1, limit, false), Wrappers.lambdaQuery(AgentTurn.class)
                .in(AgentTurn::getStatus, AgentTurnState.activeCodes())
                .orderByAsc(AgentTurn::getOrdinal));
    }
}
