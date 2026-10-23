package dev.amai.portfolio.blog.component;

import org.commonmark.node.Block;
import org.commonmark.parser.block.AbstractBlockParser;
import org.commonmark.parser.block.BlockContinue;
import org.commonmark.parser.block.BlockStart;
import org.commonmark.parser.block.ParserState;

/** 展示公式可含空行；必须在块解析阶段保护，避免其中的引用定义先被 CommonMark 收走。 */
final class MarkdownMathBlockParser extends AbstractBlockParser {
    // CommonMark 以四列区分代码缩进，制表符也按四列对齐；不使用业务配置改变 Markdown 语义。
    private static final int INDENT_WIDTH = 4;

    private final MarkdownMathBlock block = new MarkdownMathBlock();
    private final int closingOffset;

    private MarkdownMathBlockParser(int closingOffset) {
        this.closingOffset = closingOffset;
    }

    static BlockStart start(ParserState state, String source) {
        int index = state.getNextNonSpaceIndex();
        var span = state.getLine().getSourceSpan();
        if (state.getIndent() >= INDENT_WIDTH || span == null) {
            // 缩进代码仍是代码，不以公式规则改变已有 Markdown 结构。
            return BlockStart.none();
        }
        int opening = span.getInputIndex() + index;
        if (!source.startsWith("$$", opening) && !source.startsWith("\\[", opening)) {
            return BlockStart.none();
        }
        int end = MarkdownMathSyntax.end(source, opening);
        if (end < 0 || !sameContainer(state, source, opening, end)) {
            return BlockStart.none();
        }
        int lineEnd = source.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        if (!source.substring(end, lineEnd).isBlank()) {
            // 闭合符后还有文字时交给行内解析，不能整行吞掉公式外的真实附件。
            return BlockStart.none();
        }
        return BlockStart.of(new MarkdownMathBlockParser(end - 1)).atIndex(index);
    }

    private static boolean sameContainer(ParserState state, String source, int opening, int end) {
        var line = state.getLine().getContent();
        int quoteDepth = 0;
        for (int index = 0; index < state.getIndex(); index++) {
            if (line.charAt(index) == '>') {
                quoteDepth++;
            }
        }
        int newline = source.indexOf('\n', opening);
        while (newline >= 0 && newline + 1 < end) {
            int lineStart = newline + 1;
            int index = lineStart;
            int column = 0;
            int quotes = 0;
            while (index < source.length()) {
                char current = source.charAt(index);
                if (current == '>') {
                    quotes++;
                    column++;
                } else if (current == ' ' || current == '\t') {
                    column += current == '\t' ? INDENT_WIDTH - column % INDENT_WIDTH : 1;
                } else {
                    break;
                }
                index++;
            }
            boolean blank = index == source.length()
                    || source.charAt(index) == '\n' || source.charAt(index) == '\r';
            if (quotes < quoteDepth || (!blank && column < state.getColumn())) {
                // 引用/列表已经结束，后面的闭合符不属于当前公式；不能把当前容器中的真实附件隐藏掉。
                return false;
            }
            newline = source.indexOf('\n', lineStart);
        }
        return true;
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
        var span = state.getLine().getSourceSpan();
        if (span.getInputIndex() > closingOffset) {
            return BlockContinue.none();
        }
        // 包含闭合符的行属于公式；下一行重新参与普通 Markdown 解析。
        return BlockContinue.atIndex(state.getIndex());
    }
}
