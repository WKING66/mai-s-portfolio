package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.vo.DocumentResourceVo;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.system.api.TagData;
import dev.amai.portfolio.system.api.TagKind;
import java.util.List;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;

/** 只转换同一业务事实；生命周期、锁和内容选择由 Service 确定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DocumentConvert {
    DocumentConvert MAPPER = Mappers.getMapper(DocumentConvert.class);

    @Mapping(target = "id", source = "identity.id")
    @Mapping(target = "contentId", source = "content.id")
    @Mapping(target = "stage", source = "content.stage")
    Document to(DocumentDO identity, DocumentContentDO content,
            boolean hasDraft, boolean hasPublished, List<DocumentResource> resources,
            List<TagData> tags);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "documentId", ignore = true)
    @Mapping(target = "stage", ignore = true)
    @Mapping(target = "content", source = "prepared.content")
    @Mapping(target = "readingTimeMinutes", ignore = true)
    @Mapping(target = "updatedBy", source = "editor")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    DocumentContentDO to(DocumentDraft draft);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tagId", source = "tag.id")
    DocumentContentTagDO to(TagData tag, long contentId);

    DocumentResource to(DocumentResourceDO relation);

    List<DocumentResource> to(List<DocumentResourceDO> relations);

    @Mapping(target = "id", ignore = true)
    DocumentResourceDO to(DocumentResource resource, long contentId, int sortOrder);

    @Mapping(target = "type", source = "resource.type")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "originalUri", source = "originalUri")
    @Mapping(target = "filename", source = "asset.originalFilename")
    @Mapping(target = "errorMessage", source = "errorMessage")
    DocumentResourceVo to(DocumentResource resource, AssetResourceData asset,
            ResourceStatus status, String originalUri, String errorMessage);

    default ContentStage toContentStage(Integer code) {
        return ContentStage.fromCode(code);
    }

    default Integer toTagKind(String kind) {
        return TagKind.valueOf(kind).code();
    }

    default ResourceType toResourceType(Integer code) {
        return ResourceType.from(code);
    }

    default ResourceSource toResourceSource(Integer code) {
        return ResourceSource.from(code);
    }

    default ResourceStatus toResourceStatus(Integer code) {
        return ResourceStatus.from(code);
    }

    default int to(ResourceType type) {
        return type.code();
    }

    default int to(ResourceSource source) {
        return source.code();
    }

    default int to(ResourceStatus status) {
        return status.code();
    }
}
