package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.vo.DocumentResourceVo;
import dev.amai.portfolio.blog.enums.*;
import java.time.LocalDateTime;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 文档存储与投影的字段转换；不查询数据库、不签名 URL、不决定版本及资源的业务状态。 */
@Mapper(
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        imports = {ResourceType.class, ResourceSource.class, ResourceStatus.class})
public interface DocumentConvert {
    DocumentConvert MAPPER = Mappers.getMapper(DocumentConvert.class);

    /** 列表摘要采用调用方已选择的锁版本或内容版本号。 */
    @Mapping(target = "version", source = "version")
    DocumentSummary to(DocumentVersionDO documentVersion, long version);

    /** 文档身份、正文版本和资源关系合成统一快照。 */
    @Mapping(target = "id", source = "identity.id")
    @Mapping(target = "versionId", source = "snapshot.id")
    @Mapping(target = "version", source = "snapshot.versionNumber")
    @Mapping(target = "content", source = "snapshot.markdownContent")
    Document to(DocumentDO identity, DocumentVersionDO snapshot, List<DocumentResource> resources);

    /** 存储编码恢复为领域枚举，继续由 DocumentResource 构造约束保护合法组合。 */
    @Mapping(target = "key", source = "resourceKey")
    @Mapping(target = "type", expression = "java(ResourceType.from(relation.getResourceType()))")
    @Mapping(target = "source", expression = "java(ResourceSource.from(relation.getSourceKind()))")
    @Mapping(target = "status", expression = "java(ResourceStatus.from(relation.getStatus()))")
    @Mapping(target = "originalUri", source = "sourceReference")
    @Mapping(target = "assetId", source = "mediaId")
    DocumentResource to(DocumentResourceDO relation);

    /** 按数据库返回顺序恢复当前版本的资源，不重排引用。 */
    List<DocumentResource> to(List<DocumentResourceDO> relations);

    /** 装配新的不可变正文版本；阅读时间、编辑人和时间点在业务层计算。 */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "documentId", source = "identity.id")
    @Mapping(target = "versionNumber", source = "identity.lockVersion")
    @Mapping(target = "markdownContent", source = "prepared.content")
    @Mapping(target = "createdBy", source = "editor")
    @Mapping(target = "createdAt", source = "createdAt")
    DocumentVersionDO to(
            DocumentDO identity,
            PreparedDocument prepared,
            String title,
            String summary,
            long editor,
            short readingTimeMinutes,
            LocalDateTime createdAt);

    /** 资源关系按目标版本与顺序写入，不复制数据库主键。 */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "altText", ignore = true)
    @Mapping(target = "mediaId", source = "resource.assetId")
    @Mapping(target = "resourceKey", source = "resource.key")
    @Mapping(target = "resourceType", expression = "java(resource.type().code())")
    @Mapping(target = "sourceKind", expression = "java(resource.source().code())")
    @Mapping(target = "sourceReference", source = "resource.originalUri")
    @Mapping(target = "status", expression = "java(resource.status().code())")
    DocumentResourceDO to(
            DocumentResource resource, long documentVersionId, int role, int sortOrder);

    /** 合并当前资产元数据，空资产字段由生成代码统一留空；状态和来源可见性不在此判断。 */
    @Mapping(target = "type", source = "resource.type")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "originalUri", source = "originalUri")
    @Mapping(target = "filename", source = "asset.originalFilename")
    DocumentResourceVo to(
            DocumentResource resource,
            AssetResourceData asset,
            ResourceStatus status,
            String originalUri);
}
