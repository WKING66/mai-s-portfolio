package dev.amai.portfolio.blog.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;

/** 一棵 Markdown AST 的地址改写与源码编辑收集；每次 rewrite 创建一个实例。 */
final class MarkdownDestinationVisitor extends AbstractVisitor {
    private final BiFunction<String, Boolean, String> destination;
    private final List<Edit> edits = new ArrayList<>();

    /** 节点保留 AST 引用，延后渲染时才能包含所有嵌套子节点的修改。 */
    record Edit(
            int start,

            int end,

            Node node) {}

    MarkdownDestinationVisitor(BiFunction<String, Boolean, String> destination) {
        this.destination = destination;
    }

    @Override
    public void visit(Image node) {
        String before = node.getDestination();
        node.setDestination(destination.apply(before, true));
        changed(node, before, node.getDestination());
        visitChildren(node);
    }

    @Override
    public void visit(Link node) {
        String before = node.getDestination();
        node.setDestination(destination.apply(before, false));
        changed(node, before, node.getDestination());
        visitChildren(node);
    }

    List<Edit> edits() {
        return List.copyOf(edits);
    }

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
        edits.add(new Edit(first.getInputIndex(), last.getInputIndex() + last.getLength(), node));
    }
}
