package dev.amai.portfolio.blog.service;

import dev.amai.portfolio.blog.entity.domain.*;
import java.util.Map;
import java.util.Set;

/** 文档身份、当前内容及关系的事务边界；不处理博客可见性或外部文件 IO。 */
public interface DocumentService {
    Map<Long, DocumentSummary> summaries(Set<Long> ids, boolean manage);

    Document read(long id, boolean manage);

    /** 同文档草稿优先、正式正文补充，绝不按 key 跨文档检索。 */
    Map<String, DocumentResource> references(long id);

    DocumentDO findCreation(long owner, String requestId);

    Document create(long owner, int origin, DocumentDraft draft);

    Document save(long id, DocumentDraft draft);

    /** 锁定身份并校验修订。false 表示相同写请求已提交，不得再次执行动作。 */
    boolean beginWrite(long id, long revision, String requestId, String requestHash);

    void publish(long id);

    void discardDraft(long id);

    void delete(long id);

    void recordWrite(long id, String requestId, String requestHash);
}
