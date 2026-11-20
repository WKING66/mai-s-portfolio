package dev.amai.portfolio.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.agent.entity.AgentConversation;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
    Long insertAnonymous(@Param("owner") String owner);

    List<AgentConversation> list(@Param("owner") String owner, @Param("cursor") Long cursor,
            @Param("limit") int limit);

    default Long findAnonymous(String owner) {
        List<Long> ids = selectObjs(Wrappers.lambdaQuery(AgentConversation.class)
                .select(AgentConversation::getId)
                .eq(AgentConversation::getOwnerKey, owner)
                .eq(AgentConversation::isAnonymous, true));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    default AgentConversation findOwned(Long id, String owner) {
        return selectOne(Wrappers.lambdaQuery(AgentConversation.class)
                .eq(AgentConversation::getId, id)
                .eq(AgentConversation::getOwnerKey, owner));
    }

    default void applyFirstTitle(Long id, String title) {
        // 只有保留创建时默认标题的会话可由首问补齐显示名。
        update(Wrappers.lambdaUpdate(AgentConversation.class)
                .set(AgentConversation::getTitle, title)
                .eq(AgentConversation::getId, id)
                .apply("title = '新会话 ' || to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS')"));
    }

    default void touch(Long id) {
        update(Wrappers.lambdaUpdate(AgentConversation.class)
                .setSql("updated_at = CURRENT_TIMESTAMP")
                .eq(AgentConversation::getId, id));
    }

    default void resetTitle(Long id, String title) {
        update(Wrappers.lambdaUpdate(AgentConversation.class)
                .set(AgentConversation::getTitle, title)
                .setSql("updated_at = CURRENT_TIMESTAMP")
                .eq(AgentConversation::getId, id));
    }

    default int claimAnonymous(Long id, String visitor, String account) {
        return update(Wrappers.lambdaUpdate(AgentConversation.class)
                .set(AgentConversation::getOwnerKey, account)
                .set(AgentConversation::isAnonymous, false)
                .eq(AgentConversation::getId, id)
                .eq(AgentConversation::getOwnerKey, visitor)
                .eq(AgentConversation::isAnonymous, true));
    }
}
