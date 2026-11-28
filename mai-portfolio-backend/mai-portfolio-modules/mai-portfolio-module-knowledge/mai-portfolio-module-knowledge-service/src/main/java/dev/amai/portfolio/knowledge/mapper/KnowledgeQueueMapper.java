package dev.amai.portfolio.knowledge.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeQueueMapper {
    record Row(
            long id,

            int sourceType,

            long sourceId,

            long changeSeq,

            byte[] projectionHash,

            int action,

            int buildMode,

            int status,

            Instant dueAt,

            String leaseToken,

            Instant leaseUntil,

            int attemptCount,

            Integer lastError) {}

    record Target(
            int sourceType,

            long sourceId,

            byte[] projectionHash,

            int action,

            int buildMode,

            Instant dueAt) {}

    Row bySource(@Param("type") int type, @Param("id") long id);

    Row lockSource(@Param("type") int type, @Param("id") long id);

    Row lockLease(@Param("id") long id);

    List<Row> all();

    List<Row> expired(@Param("now") Instant now);

    List<Row> due(@Param("now") Instant now, @Param("types") List<Integer> types, @Param("limit") int limit);

    Instant nextDue(@Param("types") List<Integer> types);

    int register(@Param("target") Target target, @Param("insertOnly") boolean insertOnly);

    int updateLeased(
            @Param("row") Row row,
            @Param("status") int status,
            @Param("dueAt") Instant dueAt,
            @Param("token") String token,
            @Param("until") Instant until,
            @Param("error") Integer error);

    void update(@Param("id") long id, @Param("status") int status, @Param("dueAt") Instant dueAt,
                @Param("token") String token, @Param("until") Instant until,
                @Param("attempts") int attempts, @Param("error") Integer error);
}
