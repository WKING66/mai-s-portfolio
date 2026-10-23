package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.api.TagData;
import dev.amai.portfolio.system.api.TagKind;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.system.api.TechTagData;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.system.convert.TaxonomyConvert;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.entity.event.TagCatalogChanged;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.enums.TechGroup;
import dev.amai.portfolio.system.mapper.TagMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class TaxonomyQueryServiceImpl implements TaxonomyQueryService {
    private final TagMapper tagMapper;

    @Value("${portfolio.taxonomy.cache-ttl:PT1M}")
    private Duration catalogCacheTtl = Duration.ofMinutes(1);

    private CachedCatalog cachedCatalog;

    /** 缓存只保存内部标签目录，不包含登录权限、草稿内容或会过期的 OSS 地址。 */
    private record CachedCatalog(
            List<TagDO> tags,

            Instant expiresAt) {}

    public TaxonomyQueryServiceImpl(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    /** 博客可选两类标签；项目指定 TECH 仍只得到技术标签，不另建一套目录查询。 */
    @Override
    public List<TagData> tagOptions(String kind) {
        TagKind selected = null;
        if (kind != null) {
            try {
                selected = TagKind.valueOf(kind);
            } catch (IllegalArgumentException failure) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "标签类别仅支持 TOPIC 或 TECH");
            }
        }
        var tags = catalog().stream();
        if (selected != null) {
            int kindCode = selected.code();
            tags = tags.filter(tag -> tag.getKind() == kindCode);
        }
        if (selected == TagKind.TECH) {
            tags = tags.sorted(Comparator.comparing(TagDO::getGroupCode)
                    .thenComparing(TagDO::getSortOrder)
                    .thenComparing(TagDO::getId));
        }
        return tags
                .map(this::tagData)
                .toList();
    }

    /**
     * 标签选项和首页技术栈反复读取同一份小目录，只查一次后在内存筛选。
     * 本地缓存默认一分钟；其他实例或手工改库最多等待该时长，设 PT0S 可停用。
     * 保存文章时的标签存在性仍直接批量查库，不拿展示缓存决定业务有效性。
     */
    private synchronized List<TagDO> catalog() {
        Instant now = Instant.now();
        if (cachedCatalog != null && now.isBefore(cachedCatalog.expiresAt())) {
            return cachedCatalog.tags();
        }
        var tags = List.copyOf(tagMapper.selectList(Wrappers.<TagDO>lambdaQuery()
                .orderByAsc(TagDO::getKind, TagDO::getSortOrder, TagDO::getId)));
        cachedCatalog = new CachedCatalog(tags, now.plus(catalogCacheTtl));
        return tags;
    }

    /** 新标签提交后再使目录失效；回滚不清缓存，监听与加载串行，避免正在加载的旧目录覆盖清除结果。 */
    @TransactionalEventListener
    public synchronized void clearCatalog(TagCatalogChanged event) {
        cachedCatalog = null;
    }

    /** 保存前确认所选标签存在且不重复，避免文章保存了界面无法展示的无效标签。 */
    @Override
    public List<TagData> requireTags(List<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        var uniqueIds = new LinkedHashSet<>(tagIds);
        if (uniqueIds.size() != tagIds.size()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "标签选择重复");
        }
        var tags = tagMapper.selectByIds(uniqueIds);
        if (tags.size() != uniqueIds.size()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "所选标签不存在，请重新选择");
        }
        // 单次批量查询，按请求顺序返回；不为每个标签发起循环数据库查询。
        var byId = tags.stream()
                .collect(Collectors.toMap(TagDO::getId, this::tagData));
        return tagIds.stream()
                .map(byId::get)
                .toList();
    }

    private TagData tagData(TagDO tag) {
        String kind = TagKind.fromCode(tag.getKind()).name();
        String group = tag.getKind() == TagKind.TECH.code()
                ? TechGroup.fromCode(tag.getGroupCode()).name() : null;
        return TaxonomyConvert.MAPPER.to(tag, kind, group);
    }

    @Override
    public List<TechTagData> featuredTechTags() {
        return catalog().stream()
                .filter(tag -> tag.getKind() == TagKind.TECH.code()
                        && tag.getIsFeatured() == FeaturedStatus.FEATURED.code())
                .sorted(Comparator.comparing(TagDO::getGroupCode)
                        .thenComparing(TagDO::getSortOrder)
                        .thenComparing(TagDO::getId))
                .map(
                        tag ->
                                TaxonomyConvert.MAPPER.to(
                                        tag, TechGroup.fromCode(tag.getGroupCode()).name()))
                .toList();
    }

    /** 项目仍使用既有技术标签投影，查询和分类规则复用共享标签列表。 */
    @Override
    public List<TechTagOptionData> techTagOptions() {
        return tagOptions(TagKind.TECH.name())
                .stream()
                .map(TaxonomyConvert.MAPPER::toOption)
                .toList();
    }
}
