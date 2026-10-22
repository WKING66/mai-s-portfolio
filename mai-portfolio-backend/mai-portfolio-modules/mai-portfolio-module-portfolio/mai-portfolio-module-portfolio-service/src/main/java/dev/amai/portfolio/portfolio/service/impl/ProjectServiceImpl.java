package dev.amai.portfolio.portfolio.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.portfolio.constant.ProjectConstants;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import dev.amai.portfolio.portfolio.convert.ProjectConvert;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectLinkDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectMediaDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectTagDO;
import dev.amai.portfolio.portfolio.entity.request.ProjectLinkRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.entity.vo.ProjectCoverVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectEditorVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectItemVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectPageVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectTagVo;
import dev.amai.portfolio.portfolio.enums.ProjectLinkType;
import dev.amai.portfolio.portfolio.enums.ProjectStatus;
import dev.amai.portfolio.portfolio.enums.ProjectView;
import dev.amai.portfolio.portfolio.mapper.ProjectLinkMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectMediaMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectTagMapper;
import dev.amai.portfolio.portfolio.service.ProjectService;
import dev.amai.portfolio.system.api.TagData;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 项目及其标签/外链作为一个写入单元；公开查询不使用管理投影。 */
@Service
public class ProjectServiceImpl implements ProjectService {
    private final ProjectMapper projectMapper;
    private final ProjectLinkMapper projectLinkMapper;
    private final ProjectTagMapper projectTagMapper;
    private final TaxonomyQueryService taxonomyQueryService;
    private final ProjectMediaMapper projectMediaMapper;
    private final AssetQueryService assetQueryService;
    public ProjectServiceImpl(
            ProjectMapper projectMapper,
            ProjectLinkMapper projectLinkMapper,
            ProjectTagMapper projectTagMapper,
            TaxonomyQueryService taxonomyQueryService,
            ProjectMediaMapper projectMediaMapper,
            AssetQueryService assetQueryService) {
        this.projectMapper = projectMapper;
        this.projectLinkMapper = projectLinkMapper;
        this.projectTagMapper = projectTagMapper;
        this.taxonomyQueryService = taxonomyQueryService;
        this.projectMediaMapper = projectMediaMapper;
        this.assetQueryService = assetQueryService;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectPageVo list(ProjectListRequest request) {
        // 1. 先确定查询视图；管理视图权限由 Controller 声明并在请求入口校验。
        ProjectView view = parseView(request.view());
        int page = ObjectUtil.defaultIfNull(request.page(), ProjectConstants.FIRST_PAGE);
        int defaultSize = view == ProjectView.PUBLIC
                ? ProjectConstants.PUBLIC_PAGE_SIZE : ProjectConstants.MANAGE_PAGE_SIZE;
        int size = ObjectUtil.defaultIfNull(request.size(), defaultSize);

        // 2. 公开列表固定为已发布状态，管理列表才接受状态过滤。
        var query = Wrappers.<ProjectDO>lambdaQuery();
        if (view == ProjectView.PUBLIC) {
            if (request.status() != null) {
                throw invalid(ProjectMessageConstants.INVALID_QUERY);
            }
            query.eq(ProjectDO::getStatus, ProjectStatus.PUBLISHED.code());
        } else if (request.status() != null && !request.status().isBlank()) {
            query.eq(ProjectDO::getStatus, parseStatus(request.status()).code());
        }
        query.orderByDesc(ProjectDO::getIsFeatured)
                .orderByAsc(ProjectDO::getSortOrder)
                .orderByDesc(ProjectDO::getId);
        // 3. 在同一只读快照内分页并批量装配关联，避免卡片逐条查询。
        var result = projectMapper.selectPage(new Page<>(page, size), query);
        List<ProjectItemVo> items = projectViews(result.getRecords(), view);
        return new ProjectPageVo(view.name(), page, size, result.getTotal(), items);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectEditorVo get(Long id) {
        return (ProjectEditorVo) projectViews(
                List.of(requireProject(id)), ProjectView.MANAGE).getFirst();
    }

    @Override
    @Transactional
    public ProjectEditorVo create(ProjectRequest request) {
        // 1. 输入形状由请求校验保证；此处核对标签、封面和外链的业务有效性。
        validateRelations(request);
        ProjectDO project = new ProjectDO();
        apply(project, request);
        ensureSlugAvailable(project.getSlug(), null);
        project.setStatus(ProjectStatus.DRAFT.code());
        project.setVersion(ProjectConstants.INITIAL_VERSION);
        project.setCreatedAt(now());
        project.setUpdatedAt(project.getCreatedAt());
        // 2. 主表和关联在同一数据库事务内写入；唯一索引处理并发占用标识。
        try {
            projectMapper.insert(project);
        } catch (DuplicateKeyException exception) {
            // 服务层已校验，唯一索引竞争失败仍转换为稳定响应，不泄露 SQL。
            throw conflict(ProjectMessageConstants.SLUG_CONFLICT);
        }
        replaceRelations(project.getId(), request);
        return get(project.getId());
    }

    @Override
    @Transactional
    public ProjectEditorVo update(Long id, ProjectRequest request) {
        // 1. 验证关联并读取当前版本；曾发布项目的稳定标识不可变。
        validateRelations(request);
        ProjectDO existing = requireProject(id);
        requireVersion(existing, request.version());
        ProjectDO replacement = new ProjectDO();
        apply(replacement, request);
        if (existing.getPublishedAt() != null && !Objects.equals(existing.getSlug(), replacement.getSlug())) {
            throw invalid(ProjectMessageConstants.SLUG_IMMUTABLE);
        }
        ensureSlugAvailable(replacement.getSlug(), id);
        if (existing.getStatus() == ProjectStatus.PUBLISHED.code()) {
            validatePublish(replacement, request.tagIds());
        }
        // 2. 用 CAS 取得行写入权；文本、版本和关联替换处于同一事务，失败整体回滚。
        var update = Wrappers.<ProjectDO>lambdaUpdate()
                .eq(ProjectDO::getId, id)
                .eq(ProjectDO::getVersion, request.version())
                .set(ProjectDO::getSlug, replacement.getSlug())
                .set(ProjectDO::getTitle, replacement.getTitle())
                .set(ProjectDO::getSummary, replacement.getSummary())
                .set(ProjectDO::getContribution, replacement.getContribution())
                .set(ProjectDO::getOutcome, replacement.getOutcome())
                .set(ProjectDO::getTimeLabel, replacement.getTimeLabel())
                .set(ProjectDO::getIsFeatured, replacement.getIsFeatured())
                .set(ProjectDO::getSortOrder, replacement.getSortOrder())
                .set(ProjectDO::getVersion, existing.getVersion() + 1)
                .set(ProjectDO::getUpdatedAt, now());
        try {
            if (projectMapper.update(null, update) != 1) {
                throw conflict(ProjectMessageConstants.CONFLICT);
            }
        } catch (DuplicateKeyException exception) {
            throw conflict(ProjectMessageConstants.SLUG_CONFLICT);
        }
        // 3. 只替换项目持有的关联，不修改 Asset 或删除 OSS 对象。
        replaceRelations(id, request);
        return get(id);
    }

    @Override
    @Transactional
    public ProjectEditorVo publish(Long id, ProjectVersionRequest request) {
        return transition(id, request, ProjectStatus.PUBLISHED);
    }

    @Override
    @Transactional
    public ProjectEditorVo unpublish(Long id, ProjectVersionRequest request) {
        return transition(id, request, ProjectStatus.DRAFT);
    }

    private ProjectEditorVo transition(Long id, ProjectVersionRequest request, ProjectStatus target) {
        // 1. 先验证版本，发布还需确认当前标签和内容完整性。
        ProjectDO project = requireProject(id);
        requireVersion(project, request.version());
        if (target == ProjectStatus.PUBLISHED) {
            List<Long> ids = projectTagMapper.selectList(Wrappers.<ProjectTagDO>lambdaQuery()
                            .eq(ProjectTagDO::getProjectId, id))
                    .stream()
                    .map(ProjectTagDO::getTagId)
                    .toList();
            validateTagIds(ids);
            validatePublish(project, ids);
        }
        if (project.getStatus() == target.code()) {
            return get(id);
        }

        // 2. 状态与版本原子更新；下架仅隐藏公开入口，不使已签发的资源地址立即失效。
        var update = Wrappers.<ProjectDO>lambdaUpdate()
                .eq(ProjectDO::getId, id)
                .eq(ProjectDO::getVersion, request.version())
                .set(ProjectDO::getStatus, target.code())
                .set(ProjectDO::getVersion, project.getVersion() + 1)
                .set(ProjectDO::getUpdatedAt, now());
        if (target == ProjectStatus.PUBLISHED) {
            update.set(ProjectDO::getPublishedAt, now());
        }
        if (projectMapper.update(null, update) != 1) {
            throw conflict(ProjectMessageConstants.CONFLICT);
        }
        return get(id);
    }

    /** 字段长度、非负数和必填项由 Jakarta Validation 处理；此处只保留业务规则。 */
    private void validateRelations(ProjectRequest request) {
        String slug = clean(request.slug());
        if (slug != null && !slug.toLowerCase(Locale.ROOT).matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw invalid(ProjectMessageConstants.INVALID_SLUG);
        }
        validateTagIds(ObjectUtil.defaultIfNull(request.tagIds(), List.of()));
        if (request.coverMediaId() != null && Optional.ofNullable(assetQueryService.lockReady(Set.of(request.coverMediaId()))
                .get(request.coverMediaId()))
                .filter(asset -> asset.type() == AssetType.IMAGE && asset.usage() == AssetUsage.IMAGE)
                .isEmpty()) {
            throw invalid(ProjectMessageConstants.INVALID_COVER);
        }
        List<ProjectLinkRequest> inputLinks = ObjectUtil.defaultIfNull(request.links(), List.of());
        Set<String> seen = new HashSet<>();
        for (ProjectLinkRequest link : inputLinks) {
            ProjectLinkType type = parseLinkType(link.type());
            String url = clean(link.url());
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!Set.of("http", "https").contains(scheme)
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw invalid(ProjectMessageConstants.INVALID_LINKS);
            }
            if (!seen.add(type.name() + "\n" + url)) {
                throw invalid(ProjectMessageConstants.INVALID_LINKS);
            }
        }
    }

    private void validateTagIds(List<Long> ids) {
        List<TagData> tags;
        try {
            tags = taxonomyQueryService.requireTags(ids);
        } catch (ApiException failure) {
            if (failure.code() != ApiErrorCode.VALIDATION_FAILED) {
                throw failure;
            }
            throw invalid(ProjectMessageConstants.INVALID_TAGS);
        }
        if (tags.stream()
                .anyMatch(tag -> !"TECH".equals(tag.kind()))) {
            throw invalid(ProjectMessageConstants.INVALID_TAGS);
        }
    }

    private void validatePublish(ProjectDO project, List<Long> ids) {
        if (clean(project.getTitle()) == null
                || clean(project.getSummary()) == null || clean(project.getContribution()) == null
                || CollUtil.isEmpty(ids)) {
            throw invalid(ProjectMessageConstants.PUBLISH_REQUIRED);
        }
    }

    private void replaceRelations(Long id, ProjectRequest request) {
        // 封面与文本版本同事务更新；只移除引用，不删除对象或其他图集关系。
        projectMediaMapper.delete(Wrappers.<ProjectMediaDO>lambdaQuery()
                .eq(ProjectMediaDO::getProjectId, id)
                .eq(ProjectMediaDO::getRole, ProjectConstants.COVER_ROLE));
        if (request.coverMediaId() != null) {
            ProjectMediaDO cover = new ProjectMediaDO();
            cover.setProjectId(id);
            cover.setMediaId(request.coverMediaId());
            cover.setRole(ProjectConstants.COVER_ROLE);
            cover.setAltText(clean(request.title()));
            cover.setSortOrder(ProjectConstants.INITIAL_SORT_ORDER);
            projectMediaMapper.insert(cover);
        }
        projectLinkMapper.delete(Wrappers.<ProjectLinkDO>lambdaQuery().eq(ProjectLinkDO::getProjectId, id));
        projectTagMapper.delete(Wrappers.<ProjectTagDO>lambdaQuery().eq(ProjectTagDO::getProjectId, id));
        for (Long tagId : ObjectUtil.defaultIfNull(request.tagIds(), List.<Long>of())) {
            ProjectTagDO tag = new ProjectTagDO();
            tag.setProjectId(id);
            tag.setTagId(tagId);
            projectTagMapper.insert(tag);
        }
        for (ProjectLinkRequest input : ObjectUtil.defaultIfNull(request.links(), List.<ProjectLinkRequest>of())) {
            ProjectLinkDO link = new ProjectLinkDO();
            link.setProjectId(id);
            link.setLinkType(parseLinkType(input.type()).code());
            link.setLabel(clean(input.label()));
            link.setUrl(clean(input.url()));
            link.setIsVisible(input.visible() ? 1 : 0);
            link.setSortOrder(input.sortOrder());
            projectLinkMapper.insert(link);
        }
    }

    /** 一次批量读取关联，避免每张公开卡片分别查询标签与外链。 */
    private List<ProjectItemVo> projectViews(List<ProjectDO> records, ProjectView view) {
        if (CollUtil.isEmpty(records)) {
            return List.of();
        }
        // 1. 批量读取封面、外链和标签；资产签名继续复用 Asset 的统一能力。
        List<Long> ids = records.stream()
                .map(ProjectDO::getId)
                .toList();
        Map<Long, ProjectMediaDO> covers = projectMediaMapper.selectList(Wrappers.<ProjectMediaDO>lambdaQuery()
                        .in(ProjectMediaDO::getProjectId, ids)
                        .eq(ProjectMediaDO::getRole, ProjectConstants.COVER_ROLE))
                .stream()
                .collect(Collectors.toMap(ProjectMediaDO::getProjectId, Function.identity()));
        Map<Long, AssetResourceData> coverAssets = assetQueryService.findReady(
                covers.values().stream()
                        .map(ProjectMediaDO::getMediaId)
                        .collect(Collectors.toSet()),
                AssetType.IMAGE);
        List<ProjectLinkDO> allLinks = projectLinkMapper.selectList(Wrappers.<ProjectLinkDO>lambdaQuery()
                .in(ProjectLinkDO::getProjectId, ids)
                .orderByAsc(ProjectLinkDO::getSortOrder, ProjectLinkDO::getId));
        List<ProjectTagDO> allTags = projectTagMapper.selectList(Wrappers.<ProjectTagDO>lambdaQuery()
                .in(ProjectTagDO::getProjectId, ids)
                .orderByAsc(ProjectTagDO::getId));
        Map<Long, ProjectTagVo> catalog = taxonomyQueryService.techTagOptions().stream()
                .map(ProjectConvert.MAPPER::to)
                .collect(Collectors.toMap(ProjectTagVo::id, Function.identity()));
        Map<Long, List<ProjectLinkDO>> linksByProject = allLinks.stream()
                .collect(Collectors.groupingBy(ProjectLinkDO::getProjectId));
        Map<Long, List<ProjectTagDO>> tagsByProject = allTags.stream()
                .collect(Collectors.groupingBy(ProjectTagDO::getProjectId));
        // 2. 业务层过滤隐藏外链并选择投影，转换器仅负责已确定字段的装配。
        List<ProjectItemVo> result = new ArrayList<>();
        for (ProjectDO project : records) {
            ProjectMediaDO cover = covers.get(project.getId());
            AssetResourceData asset = cover == null ? null : coverAssets.get(cover.getMediaId());
            ProjectCoverVo coverVo = asset == null ? null : ProjectConvert.MAPPER.to(
                    cover, asset, cover.getAltText() == null ? project.getTitle() : cover.getAltText());
            var projectLinks = linksByProject.getOrDefault(project.getId(), List.of()).stream()
                    .filter(link -> view == ProjectView.MANAGE || link.getIsVisible() == 1)
                    .map(link -> ProjectConvert.MAPPER.to(
                            link,
                            ProjectLinkType.fromCode(link.getLinkType()).name(),
                            view == ProjectView.MANAGE ? link.getIsVisible() == 1 : null))
                    .toList();
            var projectTags = tagsByProject.getOrDefault(project.getId(), List.of()).stream()
                    .map(tag -> catalog.get(tag.getTagId()))
                    .filter(Objects::nonNull)
                    .toList();
            if (view == ProjectView.PUBLIC) {
                result.add(ProjectConvert.MAPPER.to(
                        project, projectTags, projectLinks.stream()
                                .map(ProjectConvert.MAPPER::to)
                                .toList(), coverVo));
            } else {
                result.add(ProjectConvert.MAPPER.to(
                        project,
                        projectTags,
                        projectLinks,
                        ProjectStatus.fromCode(project.getStatus()).name(),
                        project.getIsFeatured() == 1,
                        coverVo));
            }
        }
        return result;
    }

    private ProjectDO requireProject(Long id) {
        ProjectDO project = projectMapper.selectById(id);
        if (project == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, ProjectMessageConstants.NOT_FOUND);
        }
        return project;
    }

    private void requireVersion(ProjectDO project, Long version) {
        if (!project.getVersion().equals(version)) {
            throw conflict(ProjectMessageConstants.CONFLICT);
        }
    }

    private void ensureSlugAvailable(String slug, Long id) {
        if (slug == null) {
            return;
        }
        var query = Wrappers.<ProjectDO>lambdaQuery().apply("LOWER(slug) = {0}", slug);
        if (id != null) {
            query.ne(ProjectDO::getId, id);
        }
        if (projectMapper.selectCount(query) > 0) {
            throw conflict(ProjectMessageConstants.SLUG_CONFLICT);
        }
    }

    /** 规范化草稿的业务值；允许缺省文本，发布完整性由发布规则另行判断。 */
    private void apply(ProjectDO project, ProjectRequest request) {
        String slug = clean(request.slug());
        project.setSlug(slug == null ? null : slug.toLowerCase(Locale.ROOT));
        project.setTitle(clean(request.title()));
        project.setSummary(clean(request.summary()));
        project.setContribution(clean(request.contribution()));
        project.setOutcome(clean(request.outcome()));
        project.setTimeLabel(clean(request.timeLabel()));
        project.setIsFeatured(Boolean.TRUE.equals(request.featured()) ? 1 : 0);
        project.setSortOrder(ObjectUtil.defaultIfNull(request.sortOrder(), ProjectConstants.INITIAL_SORT_ORDER));
    }

    private ProjectView parseView(String view) {
        try {
            return view == null ? ProjectView.PUBLIC : ProjectView.valueOf(view);
        } catch (IllegalArgumentException exception) {
            throw invalid(ProjectMessageConstants.INVALID_QUERY);
        }
    }

    private ProjectStatus parseStatus(String status) {
        try {
            return ProjectStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw invalid(ProjectMessageConstants.INVALID_QUERY);
        }
    }

    private ProjectLinkType parseLinkType(String type) {
        try {
            return ProjectLinkType.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid(ProjectMessageConstants.INVALID_LINKS);
        }
    }

    private String clean(String value) {
        // 保留既有 isBlank + trim 的 Unicode/ASCII 边界，不以默认工具语义替代。
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }

    private ApiException conflict(String message) {
        return new ApiException(ApiErrorCode.DATA_CONFLICT, message);
    }
}
