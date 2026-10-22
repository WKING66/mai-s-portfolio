package dev.amai.portfolio.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.*;
import dev.amai.portfolio.system.entity.request.TagRequest;
import dev.amai.portfolio.system.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 共享标签入口；项目选择 TECH，博客可选择 TOPIC 与 TECH。 */
@RestController
@RequestMapping(value = "/api/v1/tags", produces = "application/json")
@SaCheckRole(AuthConstants.OWNER_ROLE)
@SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
@Tag(name = "标签")
public class TagController {
    private final TaxonomyQueryService taxonomyQueryService;

    private final TagService tagService;

    public TagController(TaxonomyQueryService taxonomyQueryService, TagService tagService) {
        this.taxonomyQueryService = taxonomyQueryService;
        this.tagService = tagService;
    }

    @GetMapping
    @Operation(summary = "读取可选标签", description = "kind=TOPIC 或 TECH；不传返回两类。项目继续指定 TECH。")
    public R<List<TagData>> list(@RequestParam(required = false) String kind) {
        return R.success(taxonomyQueryService.tagOptions(kind));
    }

    @PostMapping
    @Operation(summary = "创建主题或技术标签", description = "TECH 必须指定技术分组，TOPIC 不指定；名称和 slug 唯一冲突返回 409。")
    public R<TagData> create(@Valid @RequestBody TagRequest request) {
        return R.success(tagService.create(request));
    }
}
