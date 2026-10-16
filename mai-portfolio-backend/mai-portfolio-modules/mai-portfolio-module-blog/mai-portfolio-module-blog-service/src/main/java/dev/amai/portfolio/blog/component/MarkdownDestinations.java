package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.blog.component.MarkdownDestinationVisitor.Edit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.BiFunction;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

/** 只改实际图片/链接的源码范围；其余 Markdown（含扩展语法与代码）逐字符保留。 */
final class MarkdownDestinations {
    private MarkdownDestinations() {}

    static String rewrite(String content, BiFunction<String, Boolean, String> destination) {
        // 1. 解析后交给具名 Visitor 改写真实节点，并收集其原始源码区间。
        var markdownAst = Parser.builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build()
                .parse(content);
        var visitor = new MarkdownDestinationVisitor(destination);
        markdownAst.accept(visitor);
        var edits = new ArrayList<>(visitor.edits());
        // 2. 外层链接渲染包含已修改的子图片，只应用最外层非重叠区间。
        edits.sort(Comparator.comparingInt(Edit::start)
                .thenComparing(Comparator.comparingInt(Edit::end).reversed()));
        var outerEdits = new ArrayList<Edit>();
        for (var edit : edits) {
            // 遍历全部候选，跳过已被外层区间覆盖的编辑，并非只执行一次。
            if (outerEdits.isEmpty() || edit.start() >= outerEdits.getLast().end()) {
                outerEdits.add(edit);
            }
        }
        var rewrittenMarkdown = new StringBuilder(content);
        var markdownRenderer = MarkdownRenderer.builder().build();
        // 3. 从尾向前应用变更，避免源码偏移失效；非资源内容保持原样。
        for (var edit : outerEdits.reversed()) {
            rewrittenMarkdown.replace(edit.start(), edit.end(), markdownRenderer.render(edit.node()));
        }
        return rewrittenMarkdown.toString();
    }
}
