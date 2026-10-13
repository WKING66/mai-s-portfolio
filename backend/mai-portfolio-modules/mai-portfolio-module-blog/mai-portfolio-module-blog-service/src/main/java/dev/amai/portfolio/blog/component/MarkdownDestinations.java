package dev.amai.portfolio.blog.component;

import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

import java.util.*;
import java.util.function.BiFunction;

/** 只改实际图片/链接的源码范围；其余 Markdown（含扩展语法与代码）逐字符保留。 */
final class MarkdownDestinations {
    private MarkdownDestinations() {}

    private record Edit(int start, int end, Node node) {}

    static String rewrite(String content, BiFunction<String, Boolean, String> destination) {
        var tree = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build().parse(content);
        var edits = new ArrayList<Edit>();
        tree.accept(new AbstractVisitor() {
            private void changed(Node node, String before, String after) {
                if (Objects.equals(before, after)) return;
                var spans = node.getSourceSpans();
                if (spans.isEmpty()) throw new IllegalStateException("Missing Markdown source position");
                var first = spans.getFirst();
                var last = spans.getLast();
                edits.add(new Edit(first.getInputIndex(), last.getInputIndex() + last.getLength(), node));
            }
            @Override public void visit(Image node) {
                var before = node.getDestination();
                node.setDestination(destination.apply(before, true));
                changed(node, before, node.getDestination());
                visitChildren(node);
            }
            @Override public void visit(Link node) {
                var before = node.getDestination();
                node.setDestination(destination.apply(before, false));
                changed(node, before, node.getDestination());
                visitChildren(node);
            }
        });
        // 外层资源链接可能包含图片；渲染已修改的整棵子树，仅应用最外层范围一次。
        edits.sort(Comparator.comparingInt(Edit::start).thenComparing(Comparator.comparingInt(Edit::end).reversed()));
        var outer = new ArrayList<Edit>();
        for (var edit : edits)
            if (outer.isEmpty() || edit.start() >= outer.getLast().end()) outer.add(edit);
        var result = new StringBuilder(content);
        var renderer = MarkdownRenderer.builder().build();
        for (var edit : outer.reversed())
            result.replace(edit.start(), edit.end(), renderer.render(edit.node()));
        return result.toString();
    }
}
