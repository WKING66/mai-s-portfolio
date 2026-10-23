package dev.amai.portfolio.blog.component;

import java.util.Set;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;

/** 在 CommonMark 处理转义、链接和图片前消费完整公式；代码节点仍由原解析器处理。 */
final class MarkdownMathInlineParser implements InlineContentParserFactory, InlineContentParser {
    private final String source;

    MarkdownMathInlineParser(String source) {
        this.source = source;
    }

    @Override
    public Set<Character> getTriggerCharacters() {
        return Set.of('$', '\\');
    }

    @Override
    public InlineContentParser create() {
        return this;
    }

    @Override
    public ParsedInline tryParse(InlineParserState state) {
        var scanner = state.scanner();
        var start = scanner.position();
        char first = scanner.peek();
        scanner.next();
        var delimiter = MarkdownMathSyntax.delimiter("" + first + scanner.peek(), 0);
        scanner.setPosition(start);
        if (delimiter == null) {
            // 普通 LaTeX/Markdown 转义无需复制或扫描后续整段正文。
            return ParsedInline.none();
        }
        scanner.next(delimiter.opening);
        int length = 0;
        int backslashes = 0;
        char previous = '\0';
        while (scanner.hasNext()) {
            char current = scanner.peek();
            if (!delimiter.multiline && current == '\n') {
                break;
            }
            var candidate = scanner.position();
            if (backslashes % 2 == 0 && scanner.next(delimiter.closing)) {
                if (length > 0 && delimiter.closes(previous, scanner.peek())) {
                    var fragment = scanner.getSource(start, scanner.position());
                    var spans = fragment.getSourceSpans();
                    var firstSpan = spans.getFirst();
                    var lastSpan = spans.getLast();
                    // 使用原始源码范围保留 CRLF；外层真实链接改写时也不能重新转义公式。
                    String literal = source.substring(firstSpan.getInputIndex(),
                            lastSpan.getInputIndex() + lastSpan.getLength());
                    return ParsedInline.of(new MarkdownMathInline(literal), scanner.position());
                }
                scanner.setPosition(candidate);
            }
            scanner.next();
            length++;
            backslashes = current == '\\' ? backslashes + 1 : 0;
            previous = current;
        }
        // 失败时不消费输入：未闭合标记不能遮住后面的真实图片或附件。
        scanner.setPosition(start);
        return ParsedInline.none();
    }
}
