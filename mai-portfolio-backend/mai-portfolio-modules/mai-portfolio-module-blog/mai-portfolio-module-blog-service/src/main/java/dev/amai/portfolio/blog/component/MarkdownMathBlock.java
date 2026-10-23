package dev.amai.portfolio.blog.component;

import org.commonmark.node.CustomBlock;

/** 独立展示公式没有 Markdown 子节点，内部引用定义也不能泄漏到正文其他位置。 */
final class MarkdownMathBlock extends CustomBlock {}
