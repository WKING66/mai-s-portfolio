package dev.amai.portfolio.agent.mapper;

import dev.amai.portfolio.agent.entity.AgentConversation;
import dev.amai.portfolio.agent.entity.AgentTurn;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentConversationMapper {
    UUID insertConversation(@Param("id") UUID id, @Param("owner") String owner,
            @Param("anonymous") boolean anonymous, @Param("importId") UUID importId);

    UUID findAnonymous(@Param("owner") String owner);

    UUID findImport(@Param("owner") String owner, @Param("importId") UUID importId);

    AgentConversation findConversation(@Param("id") UUID id, @Param("owner") String owner);

    UUID lockConversation(@Param("id") UUID id, @Param("owner") String owner);

    List<AgentConversation> list(@Param("owner") String owner, @Param("cursor") UUID cursor,
            @Param("limit") int limit);

    List<AgentTurn> turns(@Param("id") UUID conversationId, @Param("limit") int limit);

    List<AgentTurn> recentCompleted(@Param("id") UUID conversationId);

    AgentTurn byRequest(@Param("id") UUID requestId);

    AgentTurn byExecution(@Param("id") UUID executionId, @Param("owner") String owner);

    boolean active(@Param("id") UUID conversationId);

    int insertTurn(@Param("id") UUID requestId, @Param("conversation") UUID conversationId,
            @Param("question") String question, @Param("answer") String answer,
            @Param("status") short status, @Param("deadline") OffsetDateTime deadline);

    int attach(@Param("request") UUID requestId, @Param("execution") UUID executionId);

    int saveTerminal(@Param("request") UUID requestId, @Param("answer") String answer,
            @Param("status") short status, @Param("error") String error);

    void touch(@Param("id") UUID conversationId);

    List<AgentTurn> unfinished(@Param("limit") int limit);
}
