package dev.amai.portfolio.blog.component;

import java.util.Set;
import org.commonmark.node.Node;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.markdown.MarkdownNodeRendererContext;
import org.commonmark.renderer.markdown.MarkdownNodeRendererFactory;

/** 外层真实链接需要重写时，标签里的公式仍输出原文，不能被转义或再次解析。 */
final class MarkdownMathRenderer implements NodeRenderer {
    private final MarkdownNodeRendererContext context;

    MarkdownMathRenderer(MarkdownNodeRendererContext context) {
        this.context = context;
    }

    /** CommonMark 要求提供具名工厂；每次渲染使用当次输出器，不跨文档保存状态。 */
    static final class Factory implements MarkdownNodeRendererFactory {
        @Override
        public NodeRenderer create(MarkdownNodeRendererContext context) {
            return new MarkdownMathRenderer(context);
        }

        @Override
        public Set<Character> getSpecialCharacters() {
            return Set.of();
        }
    }

    @Override
    public Set<Class<? extends Node>> getNodeTypes() {
        return Set.of(MarkdownMathInline.class);
    }

    @Override
    public void render(Node node) {
        context.getWriter().raw(((MarkdownMathInline) node).source());
    }
}
