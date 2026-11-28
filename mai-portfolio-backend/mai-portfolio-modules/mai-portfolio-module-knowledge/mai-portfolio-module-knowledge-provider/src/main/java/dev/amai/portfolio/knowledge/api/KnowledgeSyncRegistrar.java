package dev.amai.portfolio.knowledge.api;

/** 调用方在来源写事务中持有主行锁。 */
public interface KnowledgeSyncRegistrar {
    void register(KnowledgeSourceType source, long id, KnowledgeBuildMode mode, boolean purge, boolean changed);
}
