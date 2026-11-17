package dev.amai.portfolio.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.agent.entity.AgentModelAttempt;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentModelBudgetMapper extends BaseMapper<AgentModelAttempt> {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(#{key})")
    int lockBudget(@Param("key") long key);

    BudgetTotals totals(@Param("day") LocalDate day, @Param("currency") String currency);

    @Select("SELECT EXISTS (SELECT 1 FROM agent_model_attempt_cancelled WHERE attempt_id = #{attemptId})")
    boolean wasCancelled(@Param("attemptId") String attemptId);

    @Insert("""
            INSERT INTO agent_model_attempt_cancelled (attempt_id) VALUES (#{attemptId})
            ON CONFLICT (attempt_id) DO NOTHING
            """)
    int markCancelled(@Param("attemptId") String attemptId);

    record BudgetTotals(
            BigDecimal occupied,

            boolean blocked) {}
}
