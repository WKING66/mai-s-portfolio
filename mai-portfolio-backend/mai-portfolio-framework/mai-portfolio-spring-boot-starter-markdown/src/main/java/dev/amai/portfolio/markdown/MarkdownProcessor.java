package dev.amai.portfolio.markdown;

import dev.amai.portfolio.markdown.MarkdownDestinationVisitor.Edit;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.BiFunction;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

/**
 * 通用 Markdown 文本处理入口，隐藏 CommonMark AST 和公式实现。
 * 这里只处理文本，不识别 Blog/Document、资源协议、文件归属，也不执行数据库或网络操作。
 */
public final class MarkdownProcessor {
    private static final int TITLE_HEADING_LEVEL = 1;

    private MarkdownProcessor() {
    }

    /**
     * 严格解码 UTF-8，移除起始 BOM 并将平台换行统一成 LF。
     * 非法字节抛出解码异常，业务入口决定用户提示，不静默保存乱码。
     * 文件类型、容量及正文是否允许空字符由调用业务确定。
     */
    public static String normalizeUtf8(byte[] input) throws CharacterCodingException {
        String content = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input))
                .toString();
        if (content.startsWith("\ufeff")) {
            content = content.substring(1);
        }
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 返回首个顶层 H1 的文本；没有 H1 时返回空，文件名等默认标题由业务入口决定。
     * 沿用导入规则，只收集 Text 节点，不把行内代码、HTML 字面量当成标题。
     */
    public static Optional<String> firstHeading(String content) {
        Node markdownAst = Parser.builder().build().parse(content);
        for (Node child = markdownAst.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading && heading.getLevel() == TITLE_HEADING_LEVEL) {
                var headingText = new StringBuilder();
                appendHeadingText(child, headingText);
                return Optional.of(headingText.toString());
            }
        }
        return Optional.empty();
    }

    private static void appendHeadingText(Node node, StringBuilder headingText) {
        if (node instanceof Text textNode) {
            headingText.append(textNode.getLiteral());
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            appendHeadingText(child, headingText);
        }
    }

    /**
     * 只改写真实链接/图片，公式和代码中的示例不触发回调，未改动的源码区间保持原样。
     * 回调第二个参数 true 表示图片，false 表示链接；返回原地址即保持该目标不变。
     * 地址及签名查询参数视为不透明文本；回调异常向外传播，由业务调用方负责补偿。
     */
    public static String rewriteDestinations(String content, BiFunction<String, Boolean, String> destination) {
        // 1. 公式先作为不可拆分源码消费，内部伪图片/附件没有资源回调，也不会被上传或导出打包。
        // 其余内容沿用 CommonMark；Visitor 只改真实节点并保留原始源码区间。
        var markdownAst = Parser.builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .customBlockParserFactory((state, matched) -> MarkdownMathBlockParser.start(state, content))
                .customInlineContentParserFactory(new MarkdownMathInlineParser(content))
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
        var markdownRenderer = MarkdownRenderer.builder()
                .nodeRendererFactory(new MarkdownMathRenderer.Factory())
                .build();
        // 3. 从尾向前应用变更，避免源码偏移失效；非资源内容保持原样。
        for (var edit : outerEdits.reversed()) {
            rewrittenMarkdown.replace(edit.start(), edit.end(), markdownRenderer.render(edit.node()));
        }
        return rewrittenMarkdown.toString();
    }
}
