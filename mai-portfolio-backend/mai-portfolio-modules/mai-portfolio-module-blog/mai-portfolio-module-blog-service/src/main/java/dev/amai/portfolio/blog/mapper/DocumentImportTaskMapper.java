package dev.amai.portfolio.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.blog.entity.domain.DocumentImportTaskDO;
import org.apache.ibatis.annotations.*;

/** SQL 只负责追踪字段投影；何时成功或失败由导入业务事务决定。 */
@Mapper
public interface DocumentImportTaskMapper extends BaseMapper<DocumentImportTaskDO> {
    @Update("""
            UPDATE document_import_task SET document_id = #{documentId},
                status = CASE WHEN EXISTS (
                    SELECT 1 FROM document_resource r JOIN document_content c ON c.id = r.content_id
                    WHERE c.document_id = #{documentId} AND c.stage = 'DRAFT' AND r.status <> 0
                ) THEN 'SUCCEEDED_WITH_ISSUES' ELSE 'SUCCEEDED' END,
                issues_json = COALESCE((
                    SELECT jsonb_agg(jsonb_build_object('key', r.resource_key,
                        'status', r.status, 'errorMessage', r.error_message) ORDER BY r.sort_order)
                    FROM document_resource r JOIN document_content c ON c.id = r.content_id
                    WHERE c.document_id = #{documentId} AND c.stage = 'DRAFT' AND r.status <> 0
                ), '[]'::jsonb),
                source_sha256 = #{sha256}, updated_at = timezone('UTC', now()),
                finished_at = timezone('UTC', now())
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int succeeded(@Param("id") long id, @Param("documentId") long documentId,
            @Param("sha256") String sha256);

    @Update("""
            UPDATE document_import_task SET status = 'FAILED',
                issues_json = jsonb_build_object('code', #{code}, 'message', #{message}),
                updated_at = timezone('UTC', now()), finished_at = timezone('UTC', now())
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int failed(@Param("id") long id, @Param("code") String code, @Param("message") String message);
}
