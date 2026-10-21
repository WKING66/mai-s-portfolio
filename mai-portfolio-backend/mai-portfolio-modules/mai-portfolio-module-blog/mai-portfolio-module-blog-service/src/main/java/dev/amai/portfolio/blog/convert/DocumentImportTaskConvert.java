package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.blog.entity.domain.DocumentImportTaskDO;
import dev.amai.portfolio.blog.entity.vo.DocumentImportTaskVo;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 导入追踪字段投影，不推断或修改任务状态。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DocumentImportTaskConvert {
    DocumentImportTaskConvert MAPPER = Mappers.getMapper(DocumentImportTaskConvert.class);

    DocumentImportTaskVo to(DocumentImportTaskDO task);
}
