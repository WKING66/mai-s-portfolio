package dev.amai.portfolio.system.api;

/** 技术标签的跨域只读契约，不暴露 System DO 或 Mapper。 */
public record TechTagOptionData(Long id, String name, String slug, String group, String logoKey) {
}
