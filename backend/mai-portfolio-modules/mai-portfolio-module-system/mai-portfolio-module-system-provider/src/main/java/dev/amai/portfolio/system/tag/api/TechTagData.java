package dev.amai.portfolio.system.tag.api;

/** 分类标签域提供给其他业务域的技术栈数据。 */
public record TechTagData(String name, String slug, String group, String logoKey) {
}
