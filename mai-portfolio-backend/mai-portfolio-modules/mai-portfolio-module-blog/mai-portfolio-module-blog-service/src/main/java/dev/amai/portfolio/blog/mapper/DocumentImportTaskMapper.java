package dev.amai.portfolio.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.blog.entity.domain.DocumentImportTaskDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 记录导入结果和资源问题；成功记录必须与草稿、图片附件引用在同一次保存中提交。 */
@Mapper
public interface DocumentImportTaskMapper extends BaseMapper<DocumentImportTaskDO> {
    /** 草稿与引用保存后记录成功结果；有失败资源时同时保留可定位的问题。 */
    int succeeded(@Param("id") long id, @Param("documentId") long documentId,
            @Param("sha256") String sha256,
            @Param("processingStatus") int processingStatus,
            @Param("successStatus") int successStatus,
            @Param("issuesStatus") int issuesStatus);

    /** 单独保留导入失败原因；不能把已经成功的记录改写成失败。 */
    int failed(@Param("id") long id, @Param("code") String code, @Param("message") String message,
            @Param("processingStatus") int processingStatus,
            @Param("failedStatus") int failedStatus);
}
