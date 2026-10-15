package dev.amai.portfolio.portfolio.entity.request;

import dev.amai.portfolio.portfolio.constant.ProjectConstants;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import dev.amai.portfolio.validation.ValidationGroups;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 项目展示完整文本快照；不接受源码或文件。 */
@Schema(description = "项目展示完整文本快照；不接受源码或文件")
public record ProjectRequest(
        @Schema(description = "编辑时必须携带读取到的版本号；新建时忽略", minimum = "0")
        @NotNull(groups = ValidationGroups.Update.class, message = ProjectMessageConstants.INVALID_FIELDS)
        @Min(value = 0, groups = ValidationGroups.Update.class, message = ProjectMessageConstants.INVALID_FIELDS)
        Long version,

        @Schema(description = "项目稳定标识；首次发布后不能修改，最长 160 字符", example = "my-agent")
        @Size(max = ProjectConstants.MAX_SLUG_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String slug,

        @Schema(description = "项目标题，最长 200 字符；发布时必填")
        @Size(max = ProjectConstants.MAX_TITLE_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String title,

        @Schema(description = "项目摘要，最长 16000 字符；发布时必填")
        @Size(max = ProjectConstants.MAX_TEXT_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String summary,

        @Schema(description = "本人贡献，最长 16000 字符；发布时必填")
        @Size(max = ProjectConstants.MAX_TEXT_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String contribution,

        @Schema(description = "项目成果，可缺省，最长 16000 字符")
        @Size(max = ProjectConstants.MAX_TEXT_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String outcome,

        @Schema(description = "展示时间文本，可缺省，最长 100 字符")
        @Size(max = ProjectConstants.MAX_TIME_LABEL_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String timeLabel,

        @Schema(description = "技术标签 ID 完整集合，最多 50 个、不重复；只能来自 TECH 标签选择接口")
        @Size(max = ProjectConstants.MAX_ASSOCIATIONS, message = ProjectMessageConstants.INVALID_TAGS)
        List<
                @NotNull(message = ProjectMessageConstants.INVALID_TAGS)
                @Positive(message = ProjectMessageConstants.INVALID_TAGS)
                Long> tagIds,

        @Schema(description = "外部入口完整集合，最多 50 个；不接收源码文件或媒体上传")
        @Size(max = ProjectConstants.MAX_ASSOCIATIONS, message = ProjectMessageConstants.INVALID_LINKS)
        List<
                @Valid
                @NotNull(message = ProjectMessageConstants.INVALID_LINKS)
                ProjectLinkRequest> links,

        @Schema(description = "是否重点展示；缺省为 false")
        Boolean featured,

        @Schema(description = "手动排序，非负整数、升序；缺省为 0", minimum = "0")
        @Min(value = 0, message = ProjectMessageConstants.INVALID_FIELDS)
        Integer sortOrder,

        @Schema(description = "封面上传接口返回的 READY 项目图片 ID；null 移除关联，不删除 OSS 对象", minimum = "1")
        @Positive(message = ProjectMessageConstants.INVALID_COVER)
        Long coverMediaId
) { }
