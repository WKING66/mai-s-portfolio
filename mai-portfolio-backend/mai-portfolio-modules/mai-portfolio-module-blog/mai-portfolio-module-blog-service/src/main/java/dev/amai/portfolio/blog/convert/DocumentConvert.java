package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.vo.DocumentResourceVo;
import dev.amai.portfolio.blog.enums.*;
import java.time.LocalDateTime;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** 文档存储与投影的字段转换；不查询数据库、不签名 URL、不决定版本及资源的业务状态。 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        imports = {ResourceType.class, ResourceSource.class, ResourceStatus.class})
public interface DocumentConvert {
    @Mapping(target = "version", source = "version")
    DocumentSummary toSummary(DocumentVersionDO documentVersion, long version);

    @Mapping(target = "id", source = "identity.id")
    @Mapping(target = "versionId", source = "snapshot.id")
    @Mapping(target = "version", source = "snapshot.versionNumber")
    @Mapping(target = "content", source = "snapshot.markdownContent")
    Document toDocument(
            DocumentDO identity, DocumentVersionDO snapshot, List<DocumentResource> resources);

    @Mapping(target = "key", source = "resourceKey")
    @Mapping(target = "type", expression = "java(ResourceType.from(relation.getResourceType()))")
    @Mapping(target = "source", expression = "java(ResourceSource.from(relation.getSourceKind()))")
    @Mapping(target = "status", expression = "java(ResourceStatus.from(relation.getStatus()))")
    @Mapping(target = "originalUri", source = "sourceReference")
    @Mapping(target = "assetId", source = "mediaId")
    DocumentResource toResource(DocumentResourceDO relation);

    List<DocumentResource> toResources(List<DocumentResourceDO> relations);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "documentId", source = "identity.id")
    @Mapping(target = "versionNumber", source = "identity.lockVersion")
    @Mapping(target = "markdownContent", source = "prepared.content")
    @Mapping(target = "createdBy", source = "editor")
    @Mapping(target = "createdAt", source = "createdAt")
    DocumentVersionDO toVersion(
            DocumentDO identity,
            PreparedDocument prepared,
            String title,
            String summary,
            long editor,
            short readingTimeMinutes,
            LocalDateTime createdAt);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "altText", ignore = true)
    @Mapping(target = "mediaId", source = "resource.assetId")
    @Mapping(target = "resourceKey", source = "resource.key")
    @Mapping(target = "resourceType", expression = "java(resource.type().code())")
    @Mapping(target = "sourceKind", expression = "java(resource.source().code())")
    @Mapping(target = "sourceReference", source = "resource.originalUri")
    @Mapping(target = "status", expression = "java(resource.status().code())")
    DocumentResourceDO toRelation(
            DocumentResource resource, long documentVersionId, int role, int sortOrder);

    @Mapping(target = "type", source = "resource.type")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "originalUri", source = "originalUri")
    @Mapping(target = "filename", source = "asset.originalFilename")
    DocumentResourceVo toResourceVo(
            DocumentResource resource,
            AssetResourceData asset,
            ResourceStatus status,
            String originalUri);
}
