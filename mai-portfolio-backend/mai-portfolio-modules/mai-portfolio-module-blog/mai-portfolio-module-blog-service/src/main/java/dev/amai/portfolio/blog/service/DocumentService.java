package dev.amai.portfolio.blog.service;

import dev.amai.portfolio.blog.entity.domain.*;
import java.util.Map;

/** 文档版本与资源关系的数据库边界，不处理 HTTP、来源格式、OSS URL 或博客公开权限。 */
public interface DocumentService {
    /** 每个文档的 value=null 表示最新保存版本；非 null 是指定内容版本 ID。 */
    Map<Long, DocumentSummary> summaries(Map<Long, Long> versionByDocument);

    Document create(
            long owner, int origin, String title, String summary, PreparedDocument prepared);

    Document append(
            long id,
            long expectedVersion,
            long editor,
            String title,
            String summary,
            PreparedDocument prepared);

    /** versionId=null 读取最新保存版本，否则读取属于该文档的指定不可变版本。 */
    Document read(long id, Long versionId);

    long lockVersion(long id);

    /** CAS 增加写入锁计数；自身不创建内容版本，发布/下架也复用此能力。 */
    void advanceLock(long id, long expectedVersion);
}
