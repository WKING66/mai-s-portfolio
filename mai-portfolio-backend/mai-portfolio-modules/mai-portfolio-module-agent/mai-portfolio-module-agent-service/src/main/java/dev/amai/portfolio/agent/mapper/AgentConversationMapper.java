package dev.amai.portfolio.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.agent.entity.AgentConversation;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
    String insertConversation(@Param("id") String id, @Param("owner") String owner,
            @Param("anonymous") boolean anonymous, @Param("importId") String importId);

    String lockConversation(@Param("id") String id, @Param("owner") String owner);

    List<AgentConversation> list(@Param("owner") String owner, @Param("cursor") String cursor,
            @Param("limit") int limit);

    default String findAnonymous(String owner) {
        return findId(owner, true, null);
    }

    default String findImport(String owner, String importId) {
        return findId(owner, false, importId);
    }

    private String findId(String owner, boolean anonymous, String importId) {
        // 匿名身份和导入编号各有唯一约束，冲突后的查询最多返回原会话编号。
        List<String> ids = selectObjs(Wrappers.lambdaQuery(AgentConversation.class)
                .select(AgentConversation::getId)
                .eq(AgentConversation::getOwnerKey, owner)
                .eq(AgentConversation::isAnonymous, anonymous)
                .eq(importId != null, AgentConversation::getImportId, importId));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    default boolean existsOwned(String id, String owner) {
        return exists(Wrappers.lambdaQuery(AgentConversation.class)
                .eq(AgentConversation::getId, id)
                .eq(AgentConversation::getOwnerKey, owner));
    }

    default void touch(String id) {
        update(Wrappers.lambdaUpdate(AgentConversation.class)
                .setSql("updated_at = CURRENT_TIMESTAMP")
                .eq(AgentConversation::getId, id));
    }
}
