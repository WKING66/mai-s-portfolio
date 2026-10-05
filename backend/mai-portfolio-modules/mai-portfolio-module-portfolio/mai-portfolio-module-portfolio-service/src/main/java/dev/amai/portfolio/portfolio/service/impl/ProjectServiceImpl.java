package dev.amai.portfolio.portfolio.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.portfolio.constant.ProjectConstants;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectLinkDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectTagDO;
import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectLinkRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.entity.vo.AdminProjectVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectItemVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectLinkVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectPageVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectTagVo;
import dev.amai.portfolio.portfolio.entity.vo.PublicProjectVo;
import dev.amai.portfolio.portfolio.entity.vo.PublicProjectLinkVo;
import dev.amai.portfolio.portfolio.enums.ProjectLinkType;
import dev.amai.portfolio.portfolio.enums.ProjectStatus;
import dev.amai.portfolio.portfolio.enums.ProjectView;
import dev.amai.portfolio.portfolio.mapper.ProjectMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectLinkMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectTagMapper;
import dev.amai.portfolio.portfolio.service.ProjectService;
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
    private final ProjectMapper projects;
    private final ProjectLinkMapper links;
    private final ProjectTagMapper tags;
    private final TaxonomyQueryService taxonomy;

    public ProjectServiceImpl(ProjectMapper projects, ProjectLinkMapper links,
            ProjectTagMapper tags, TaxonomyQueryService taxonomy) {
        this.projects = projects;
        this.links = links;
        this.tags = tags;
        this.taxonomy = taxonomy;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectPageVo list(ProjectListRequest request) {
        ProjectView view = parseView(request.view());
        int page = request.page() == null ? 1 : request.page();
        int size = request.size() == null ? (view == ProjectView.PUBLIC
            ? ProjectConstants.PUBLIC_PAGE_SIZE : ProjectConstants.MANAGE_PAGE_SIZE) : request.size();
        if (page < 1 || size < 1 || size > ProjectConstants.MAX_PAGE_SIZE) {
            throw invalid(ProjectMessageConstants.INVALID_QUERY);
        }
        var query = Wrappers.<ProjectDO>lambdaQuery();
        if (view == ProjectView.PUBLIC) {
            if (request.status() != null) throw invalid(ProjectMessageConstants.INVALID_QUERY);
            query.eq(ProjectDO::getStatus, ProjectStatus.PUBLISHED.code());
        } else if (request.status() != null && !request.status().isBlank()) {
            query.eq(ProjectDO::getStatus, parseStatus(request.status()).code());
        }
        query.orderByDesc(ProjectDO::getIsFeatured).orderByAsc(ProjectDO::getSortOrder)
            .orderByDesc(ProjectDO::getId);
        var result = projects.selectPage(new Page<>(page, size), query);
        List<ProjectItemVo> items = projectViews(result.getRecords(), view);
        return new ProjectPageVo(view.name(), page, size, result.getTotal(), items);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminProjectVo get(Long id) {
        return (AdminProjectVo) projectViews(List.of(requireProject(id)), ProjectView.MANAGE).getFirst();
    }

    @Override
    @Transactional
    public AdminProjectVo create(ProjectRequest request) {
        validate(request);
        ProjectDO project = new ProjectDO();
        apply(project, request);
        ensureSlugAvailable(project.getSlug(), null);
        project.setStatus(ProjectStatus.DRAFT.code());
        project.setVersion(0L);
        project.setCreatedAt(now());
        project.setUpdatedAt(project.getCreatedAt());
        try {
            projects.insert(project);
        } catch (DuplicateKeyException exception) {
            // 服务层已校验，唯一索引竞争失败仍转换为稳定响应，不泄露 SQL。
            throw conflict(ProjectMessageConstants.SLUG_CONFLICT);
        }
        replaceRelations(project.getId(), request);
        return get(project.getId());
    }

    @Override
    @Transactional
    public AdminProjectVo update(Long id, ProjectRequest request) {
        validate(request);
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
        // 先用原子版本更新取得该项目行的写入权；关联替换与其处于同一事务。
        var update = Wrappers.<ProjectDO>lambdaUpdate()
            .eq(ProjectDO::getId, id).eq(ProjectDO::getVersion, request.version())
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
            if (projects.update(null, update) != 1) throw conflict(ProjectMessageConstants.CONFLICT);
        } catch (DuplicateKeyException exception) {
            throw conflict(ProjectMessageConstants.SLUG_CONFLICT);
        }
        replaceRelations(id, request);
        return get(id);
    }

    @Override
    @Transactional
    public AdminProjectVo publish(Long id, ProjectVersionRequest request) {
        return transition(id, request, ProjectStatus.PUBLISHED);
    }

    @Override
    @Transactional
    public AdminProjectVo unpublish(Long id, ProjectVersionRequest request) {
        return transition(id, request, ProjectStatus.DRAFT);
    }

    private AdminProjectVo transition(Long id, ProjectVersionRequest request, ProjectStatus target) {
        ProjectDO project = requireProject(id);
        requireVersion(project, request.version());
        if (target == ProjectStatus.PUBLISHED) {
            List<Long> ids = tags.selectList(Wrappers.<ProjectTagDO>lambdaQuery()
                .eq(ProjectTagDO::getProjectId, id)).stream().map(ProjectTagDO::getTagId).toList();
            validateTagIds(ids);
            validatePublish(project, ids);
        }
        if (project.getStatus() == target.code()) return get(id);
        var update = Wrappers.<ProjectDO>lambdaUpdate()
            .eq(ProjectDO::getId, id).eq(ProjectDO::getVersion, request.version())
            .set(ProjectDO::getStatus, target.code())
            .set(ProjectDO::getVersion, project.getVersion() + 1)
            .set(ProjectDO::getUpdatedAt, now());
        if (target == ProjectStatus.PUBLISHED) update.set(ProjectDO::getPublishedAt, now());
        if (projects.update(null, update) != 1) throw conflict(ProjectMessageConstants.CONFLICT);
        return get(id);
    }

    private void validate(ProjectRequest request) {
        if (request == null) throw invalid(ProjectMessageConstants.INVALID_FIELDS);
        checkLength(request.slug(), 160);
        checkLength(request.title(), 200);
        checkLength(request.summary(), ProjectConstants.MAX_TEXT_LENGTH);
        checkLength(request.contribution(), ProjectConstants.MAX_TEXT_LENGTH);
        checkLength(request.outcome(), ProjectConstants.MAX_TEXT_LENGTH);
        checkLength(request.timeLabel(), 100);
        if (request.sortOrder() != null && request.sortOrder() < 0) throw invalid(ProjectMessageConstants.INVALID_FIELDS);
        String slug = clean(request.slug());
        if (slug != null && !slug.toLowerCase(Locale.ROOT).matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw invalid(ProjectMessageConstants.INVALID_SLUG);
        }
        validateTagIds(request.tagIds() == null ? List.of() : request.tagIds());
        List<ProjectLinkRequest> inputLinks = request.links() == null ? List.of() : request.links();
        if (inputLinks.size() > ProjectConstants.MAX_ASSOCIATIONS) throw invalid(ProjectMessageConstants.INVALID_LINKS);
        Set<String> seen = new HashSet<>();
        for (ProjectLinkRequest link : inputLinks) {
            if (link == null || link.url() == null || link.visible() == null
                    || link.sortOrder() == null || link.sortOrder() < 0) throw invalid(ProjectMessageConstants.INVALID_LINKS);
            ProjectLinkType type = parseLinkType(link.type());
            checkLength(link.label(), 100);
            checkLength(link.url(), 2048);
            String url = clean(link.url());
            try {
                URI uri = URI.create(url);
                if (!Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT))
                        || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw invalid(ProjectMessageConstants.INVALID_LINKS);
            }
            if (!seen.add(type.name() + "\n" + url)) throw invalid(ProjectMessageConstants.INVALID_LINKS);
        }
    }

    private void validateTagIds(List<Long> ids) {
        if (ids.size() > ProjectConstants.MAX_ASSOCIATIONS || ids.stream().anyMatch(id -> id == null || id < 1)
                || new HashSet<>(ids).size() != ids.size()) throw invalid(ProjectMessageConstants.INVALID_TAGS);
        Set<Long> available = taxonomy.techTagOptions().stream().map(tag -> tag.id()).collect(Collectors.toSet());
        if (!available.containsAll(ids)) throw invalid(ProjectMessageConstants.INVALID_TAGS);
    }

    private void validatePublish(ProjectDO project, List<Long> ids) {
        if (clean(project.getTitle()) == null
                || clean(project.getSummary()) == null || clean(project.getContribution()) == null
                || ids == null || ids.isEmpty()) throw invalid(ProjectMessageConstants.PUBLISH_REQUIRED);
    }

    private void replaceRelations(Long id, ProjectRequest request) {
        links.delete(Wrappers.<ProjectLinkDO>lambdaQuery().eq(ProjectLinkDO::getProjectId, id));
        tags.delete(Wrappers.<ProjectTagDO>lambdaQuery().eq(ProjectTagDO::getProjectId, id));
        for (Long tagId : request.tagIds() == null ? List.<Long>of() : request.tagIds()) {
            ProjectTagDO tag = new ProjectTagDO();
            tag.setProjectId(id);
            tag.setTagId(tagId);
            tags.insert(tag);
        }
        for (ProjectLinkRequest input : request.links() == null ? List.<ProjectLinkRequest>of() : request.links()) {
            ProjectLinkDO link = new ProjectLinkDO();
            link.setProjectId(id);
            link.setLinkType(parseLinkType(input.type()).code());
            link.setLabel(clean(input.label()));
            link.setUrl(clean(input.url()));
            link.setIsVisible(input.visible() ? 1 : 0);
            link.setSortOrder(input.sortOrder());
            links.insert(link);
        }
    }

    /** 一次批量读取关联，避免每张公开卡片分别查询标签与外链。 */
    private List<ProjectItemVo> projectViews(List<ProjectDO> records, ProjectView view) {
        if (records.isEmpty()) return List.of();
        List<Long> ids = records.stream().map(ProjectDO::getId).toList();
        var allLinks = links.selectList(Wrappers.<ProjectLinkDO>lambdaQuery().in(ProjectLinkDO::getProjectId, ids)
            .orderByAsc(ProjectLinkDO::getSortOrder, ProjectLinkDO::getId));
        var allTags = tags.selectList(Wrappers.<ProjectTagDO>lambdaQuery().in(ProjectTagDO::getProjectId, ids)
            .orderByAsc(ProjectTagDO::getId));
        Map<Long, ProjectTagVo> catalog = taxonomy.techTagOptions().stream()
            .map(tag -> new ProjectTagVo(tag.id(), tag.name(), tag.slug(), tag.group(), tag.logoKey()))
            .collect(Collectors.toMap(ProjectTagVo::id, Function.identity()));
        Map<Long, List<ProjectLinkDO>> linksByProject = allLinks.stream()
            .collect(Collectors.groupingBy(ProjectLinkDO::getProjectId));
        Map<Long, List<ProjectTagDO>> tagsByProject = allTags.stream()
            .collect(Collectors.groupingBy(ProjectTagDO::getProjectId));
        List<ProjectItemVo> result = new ArrayList<>();
        for (ProjectDO project : records) {
            var projectLinks = linksByProject.getOrDefault(project.getId(), List.of()).stream()
                .filter(link -> view == ProjectView.MANAGE || link.getIsVisible() == 1)
                .map(link -> new ProjectLinkVo(ProjectLinkType.fromCode(link.getLinkType()).name(),
                    link.getLabel(), link.getUrl(), view == ProjectView.MANAGE ? link.getIsVisible() == 1 : null,
                    link.getSortOrder())).toList();
            var projectTags = tagsByProject.getOrDefault(project.getId(), List.of()).stream()
                .map(tag -> catalog.get(tag.getTagId())).filter(Objects::nonNull).toList();
            if (view == ProjectView.PUBLIC) {
                result.add(new PublicProjectVo(project.getId(), project.getSlug(), project.getTitle(),
                    project.getSummary(), project.getContribution(), project.getOutcome(), project.getTimeLabel(),
                    projectTags, projectLinks.stream()
                        .map(link -> new PublicProjectLinkVo(link.type(), link.label(), link.url())).toList()));
            } else {
                result.add(new AdminProjectVo(project.getId(), project.getSlug(), project.getTitle(),
                    project.getSummary(), project.getContribution(), project.getOutcome(), project.getTimeLabel(),
                    projectTags, projectLinks, ProjectStatus.fromCode(project.getStatus()).name(),
                    project.getIsFeatured() == 1, project.getSortOrder(), project.getVersion(),
                    project.getPublishedAt(), project.getUpdatedAt()));
            }
        }
        return result;
    }

    private ProjectDO requireProject(Long id) {
        if (id == null || id < 1) throw invalid(ProjectMessageConstants.INVALID_FIELDS);
        ProjectDO project = projects.selectById(id);
        if (project == null) throw new ApiException(ApiErrorCode.NOT_FOUND, ProjectMessageConstants.NOT_FOUND);
        return project;
    }

    private void requireVersion(ProjectDO project, Long version) {
        if (version == null || version < 0) throw invalid(ProjectMessageConstants.INVALID_FIELDS);
        if (!project.getVersion().equals(version)) throw conflict(ProjectMessageConstants.CONFLICT);
    }

    private void ensureSlugAvailable(String slug, Long id) {
        if (slug == null) return;
        var query = Wrappers.<ProjectDO>lambdaQuery().apply("LOWER(slug) = {0}", slug);
        if (id != null) query.ne(ProjectDO::getId, id);
        if (projects.selectCount(query) > 0) throw conflict(ProjectMessageConstants.SLUG_CONFLICT);
    }

    private void apply(ProjectDO project, ProjectRequest request) {
        String slug = clean(request.slug());
        project.setSlug(slug == null ? null : slug.toLowerCase(Locale.ROOT));
        project.setTitle(clean(request.title()));
        project.setSummary(clean(request.summary()));
        project.setContribution(clean(request.contribution()));
        project.setOutcome(clean(request.outcome()));
        project.setTimeLabel(clean(request.timeLabel()));
        project.setIsFeatured(Boolean.TRUE.equals(request.featured()) ? 1 : 0);
        project.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    }

    private ProjectView parseView(String view) {
        try { return view == null ? ProjectView.PUBLIC : ProjectView.valueOf(view); }
        catch (IllegalArgumentException exception) { throw invalid(ProjectMessageConstants.INVALID_QUERY); }
    }

    private ProjectStatus parseStatus(String status) {
        try { return ProjectStatus.valueOf(status); }
        catch (IllegalArgumentException exception) { throw invalid(ProjectMessageConstants.INVALID_QUERY); }
    }

    private ProjectLinkType parseLinkType(String type) {
        try { return ProjectLinkType.valueOf(type); }
        catch (IllegalArgumentException | NullPointerException exception) { throw invalid(ProjectMessageConstants.INVALID_LINKS); }
    }

    private void checkLength(String value, int maximum) {
        if (value != null && value.length() > maximum) throw invalid(ProjectMessageConstants.INVALID_FIELDS);
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
    private ApiException invalid(String message) { return new ApiException(ApiErrorCode.VALIDATION_FAILED, message); }
    private ApiException conflict(String message) { return new ApiException(ApiErrorCode.DATA_CONFLICT, message); }
}
