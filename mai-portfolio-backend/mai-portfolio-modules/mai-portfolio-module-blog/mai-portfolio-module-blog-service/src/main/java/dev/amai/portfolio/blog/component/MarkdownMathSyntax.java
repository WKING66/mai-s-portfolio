package dev.amai.portfolio.blog.component;

/** 编辑与阅读约定的四类公式边界；只识别源码范围，不验证 LaTeX 是否能渲染。 */
final class MarkdownMathSyntax {
    private MarkdownMathSyntax() {}

    enum Delimiter {
        DOLLAR("$", "$", false),
        DISPLAY_DOLLAR("$$", "$$", true),
        PARENTHESES("\\(", "\\)", true),
        BRACKETS("\\[", "\\]", true);

        final String opening;

        final String closing;

        final boolean multiline;

        Delimiter(String opening, String closing, boolean multiline) {
            this.opening = opening;
            this.closing = closing;
            this.multiline = multiline;
        }

        boolean closes(char previous, char next) {
            // 数字可以开始公式，但闭合美元后紧跟数字时仍是价格文本，不能吞掉其中的真实附件。
            return this != DOLLAR || (previous != '$' && next != '$' && !Character.isDigit(next));
        }
    }

    static Delimiter delimiter(String source, int start) {
        if (source.startsWith("$$", start)) {
            return Delimiter.DISPLAY_DOLLAR;
        }
        if (source.startsWith("\\[", start)) {
            return Delimiter.BRACKETS;
        }
        if (source.startsWith("\\(", start)) {
            return Delimiter.PARENTHESES;
        }
        return source.startsWith("$", start) ? Delimiter.DOLLAR : null;
    }

    static int end(String source, int start) {
        var delimiter = delimiter(source, start);
        if (delimiter == null || escaped(source, start)) {
            return -1;
        }
        int body = start + delimiter.opening.length();
        for (int index = body; index < source.length(); index++) {
            char current = source.charAt(index);
            if (!delimiter.multiline && (current == '\n' || current == '\r')) {
                // 单美元不跨行；没有闭合符时，后面的真实文件仍按普通 Markdown 处理。
                return -1;
            }
            if (!source.startsWith(delimiter.closing, index) || escaped(source, index)) {
                continue;
            }
            char next = index + delimiter.closing.length() < source.length()
                    ? source.charAt(index + delimiter.closing.length()) : '\0';
            if (index > body && delimiter.closes(source.charAt(index - 1), next)) {
                return index + delimiter.closing.length();
            }
        }
        return -1;
    }

    private static boolean escaped(String source, int index) {
        int backslashes = 0;
        for (int previous = index - 1; previous >= 0 && source.charAt(previous) == '\\'; previous--) {
            backslashes++;
        }
        return backslashes % 2 != 0;
    }
}
