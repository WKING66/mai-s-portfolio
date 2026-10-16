package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentDO;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.entity.domain.DocumentResourceDO;
import dev.amai.portfolio.blog.entity.domain.DocumentSummary;
import dev.amai.portfolio.blog.entity.domain.DocumentVersionDO;
import dev.amai.portfolio.blog.entity.domain.PreparedDocument;
import dev.amai.portfolio.blog.entity.vo.DocumentResourceVo;
import dev.amai.portfolio.blog.enums.ResourceSource;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.enums.ResourceType;
import java.time.LocalDateTime;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 文档快照与资源字段转换；版本创建、权限和可见性均由业务层确定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DocumentConvert {
    DocumentConvert MAPPER = Mappers.getMapper(DocumentConvert.class);

    DocumentSummary to(DocumentVersionDO documentVersion, long version);

    // 文档身份 ID、内容版本 ID 和锁版本是不同业务事实，不为省略映射而混用名称。
    @Mapping(target = "id", source = "identity.id")
    @Mapping(target = "versionId", source = "snapshot.id")
    @Mapping(target = "version", source = "snapshot.versionNumber")
    Document to(DocumentDO identity, DocumentVersionDO snapshot, List<DocumentResource> resources);

    DocumentResource to(DocumentResourceDO relation);

    List<DocumentResource> to(List<DocumentResourceDO> relations);

    /** 正文快照复用同名字段；编辑人、阅读时间与本次保存时点由调用方确定。 */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "documentId", source = "identity.id")
    @Mapping(target = "versionNumber", source = "identity.lockVersion")
    @Mapping(target = "createdAt", source = "createdAt")
    DocumentVersionDO to(
            DocumentDO identity,
            PreparedDocument prepared,
            String title,
            String summary,
            long createdBy,
            short readingTimeMinutes,
            LocalDateTime createdAt);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "altText", ignore = true)
    DocumentResourceDO to(DocumentResource resource, long documentVersionId, int role, int sortOrder);

    // 状态与来源已由业务层裁剪，不能自动覆盖为领域资源中的原始值。
    @Mapping(target = "type", source = "resource.type")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "originalUri", source = "originalUri")
    @Mapping(target = "filename", source = "asset.originalFilename")
    @Mapping(target = "errorMessage", source = "errorMessage")
    DocumentResourceVo to(
            DocumentResource resource,
            AssetResourceData asset,
            ResourceStatus status,
            String originalUri,
            String errorMessage);

    // 编码转换按类型统一复用，禁止使用默认 ordinal 或在每个字段中复制 Java 表达式。
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
