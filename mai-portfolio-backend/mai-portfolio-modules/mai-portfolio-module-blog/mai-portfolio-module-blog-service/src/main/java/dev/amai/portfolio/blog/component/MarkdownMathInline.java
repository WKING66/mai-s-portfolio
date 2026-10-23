package dev.amai.portfolio.blog.component;

import org.commonmark.node.CustomNode;

/** 公式是不可拆分的源码，内部方括号和地址不再生成图片、链接子节点。 */
final class MarkdownMathInline extends CustomNode {
    private final String source;

    MarkdownMathInline(String source) {
        this.source = source;
    }

    String source() {
        return source;
    }
}
