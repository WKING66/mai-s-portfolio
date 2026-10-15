package dev.amai.portfolio.blog.component;

import java.util.*;
import java.util.function.BiFunction;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

/** 只改实际图片/链接的源码范围；其余 Markdown（含扩展语法与代码）逐字符保留。 */
final class MarkdownDestinations {
    private MarkdownDestinations() {}

    private record Edit(int start, int end, Node node) {}

    static String rewrite(String content, BiFunction<String, Boolean, String> destination) {
        // 1. 读取 CommonMark 源码范围，只标记地址变化的真实图片与链接。
        var markdownAst =
                Parser.builder()
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build()
                        .parse(content);
        var edits = new ArrayList<Edit>();
        markdownAst.accept(
                new AbstractVisitor() {
                    private void changed(Node node, String before, String after) {
                        if (Objects.equals(before, after)) {
                            return;
                        }
                        var spans = node.getSourceSpans();
                        if (spans.isEmpty()) {
                            throw new IllegalStateException("Missing Markdown source position");
                        }
                        var first = spans.getFirst();
                        var last = spans.getLast();
                        edits.add(
                                new Edit(
                                        first.getInputIndex(),
                                        last.getInputIndex() + last.getLength(),
                                        node));
                    }

                    @Override
                    public void visit(Image node) {
                        var before = node.getDestination();
                        node.setDestination(destination.apply(before, true));
                        changed(node, before, node.getDestination());
                        visitChildren(node);
                    }

                    @Override
                    public void visit(Link node) {
                        var before = node.getDestination();
                        node.setDestination(destination.apply(before, false));
                        changed(node, before, node.getDestination());
                        visitChildren(node);
                    }
                });
        // 2. 外层资源链接可能包含图片；渲染已修改的整棵子树，仅应用最外层范围一次。
        edits.sort(
                Comparator.comparingInt(Edit::start)
                        .thenComparing(Comparator.comparingInt(Edit::end).reversed()));
        var outerEdits = new ArrayList<Edit>();
        for (var edit : edits) {
            if (outerEdits.isEmpty() || edit.start() >= outerEdits.getLast().end()) {
                outerEdits.add(edit);
            }
        }
        var rewrittenMarkdown = new StringBuilder(content);
        var markdownRenderer = MarkdownRenderer.builder().build();
        // 3. 从尾向前应用变更，避免源码偏移失效；非资源内容保持原样。
        for (var edit : outerEdits.reversed()) {
            rewrittenMarkdown.replace(
                    edit.start(), edit.end(), markdownRenderer.render(edit.node()));
        }
        return rewrittenMarkdown.toString();
    }
}
