package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.MARKDOWN_EXTENSION;

import cn.hutool.core.util.ArrayUtil;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.entity.domain.ParsedDocument;
import dev.amai.portfolio.blog.enums.DocumentOrigin;
import dev.amai.portfolio.markdown.MarkdownProcessor;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.nio.charset.CharacterCodingException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Markdown 导入业务适配：校验文件范围、映射用户异常并生成 Document 输入，文本处理交给 framework。 */
@Component
public class MarkdownDocumentParser implements DocumentParser {
    private final DocumentProperties documentProperties;

    public MarkdownDocumentParser(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    @Override
    public ParsedDocument parse(
            String filename, byte[] input, Map<String, DocumentInputResource> resources) {
        // 1. 格式选择止于解析器边界，不把来源格式带入文档保存流程。
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(MARKDOWN_EXTENSION)) {
            throw new ApiException(
                    ApiErrorCode.UNSUPPORTED_MEDIA_TYPE, "目前支持 UTF-8 Markdown (.md)，DOCX/PDF 尚未启用");
        }
        if (ArrayUtil.isEmpty(input) || input.length > documentProperties.getMaxContentBytes()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "Markdown 为空或超过正文大小限制");
        }
        String content;
        try {
            // 2. 使用严格 UTF-8 解码，不能把非法字节静默替换后当成用户正文保存。
            content = MarkdownProcessor.normalizeUtf8(input);
        } catch (CharacterCodingException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "Markdown 必须使用 UTF-8 编码");
        }
        if (content.indexOf(0) >= 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "正文包含非法空字符");
        }
        String title = filename.substring(0, filename.length() - MARKDOWN_EXTENSION.length());
        // 3. 优先使用首个顶层 H1；没有 H1 时保留文件名标题，不把正文行文本误作标题。
        title = MarkdownProcessor.firstHeading(content).orElse(title);
        return new ParsedDocument(DocumentOrigin.MARKDOWN, title, content, resources);
    }
}
