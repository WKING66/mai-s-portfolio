package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.MARKDOWN_EXTENSION;

import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.entity.domain.ParsedDocument;
import dev.amai.portfolio.blog.enums.DocumentOrigin;
import dev.amai.portfolio.web.exception.*;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.nio.*;
import java.nio.charset.*;
import java.util.Locale;
import java.util.Map;

/** 只做格式解码、正文规范化和标题提取；资源上传与数据库写入由后续链路处理。 */
@Component
public class MarkdownDocumentParser implements DocumentParser {
    private final DocumentProperties limits;

    public MarkdownDocumentParser(DocumentProperties limits) {
        this.limits = limits;
    }

    @Override
    public ParsedDocument parse(
            String filename,
            byte[] input,
            Map<String, DocumentInputResource> resources) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(MARKDOWN_EXTENSION))
            throw new ApiException(
                    ApiErrorCode.UNSUPPORTED_MEDIA_TYPE, "目前支持 UTF-8 Markdown (.md)，DOCX/PDF 尚未启用");
        if (input == null || input.length == 0 || input.length > limits.getMaxContentBytes())
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "Markdown 为空或超过正文大小限制");
        String content;
        try {
            // 使用严格 UTF-8 解码，不能把非法字节静默替换后当成用户正文保存。
            content =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(input))
                            .toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "Markdown 必须使用 UTF-8 编码");
        }
        // BOM 与换行统一在导入边界处理，后续展示、编辑和导出不再区分平台编码。
        if (content.startsWith("\ufeff")) content = content.substring(1);
        content = content.replace("\r\n", "\n").replace('\r', '\n');
        if (content.indexOf(0) >= 0)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "正文包含非法空字符");
        String title = filename.substring(0, filename.length() - MARKDOWN_EXTENSION.length());
        // 优先使用首个顶层 H1；没有 H1 时保留文件名标题，不把正文行文本误作标题。
        Node tree = Parser.builder().build().parse(content);
        for (Node child = tree.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading && heading.getLevel() == 1) {
                StringBuilder text = new StringBuilder();
                child.accept(
                        new AbstractVisitor() {
                            @Override
                            public void visit(Text t) {
                                text.append(t.getLiteral());
                            }
                        });
                title = text.toString();
                break;
            }
        }
        return new ParsedDocument(
                DocumentOrigin.MARKDOWN, title, content, resources);
    }
}
