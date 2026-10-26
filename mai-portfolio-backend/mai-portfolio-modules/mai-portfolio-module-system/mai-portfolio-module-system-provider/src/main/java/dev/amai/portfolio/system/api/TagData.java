package dev.amai.portfolio.system.api;

/** 标签跨模块事实；TOPIC 不带技术分组，TECH 保留原有分组。 */
public record TagData(
        Long id,

        String name,

        String slug,

        String kind,

        String group,

        String logoKey) {}
