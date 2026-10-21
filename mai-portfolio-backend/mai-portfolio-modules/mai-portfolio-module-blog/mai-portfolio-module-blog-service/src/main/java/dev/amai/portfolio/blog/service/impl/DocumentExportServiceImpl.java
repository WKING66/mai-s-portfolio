package dev.amai.portfolio.blog.service.impl;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.IO_BUFFER_BYTES;
import static dev.amai.portfolio.blog.constant.DocumentPolicy.MARKDOWN_EXTENSION;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.blog.component.DocumentRenderer;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentExport;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.service.DocumentExportService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 已授权文档导出：正文模式使用远程链接，携带资源时将正文与资源流式写入 ZIP。 */
@Service
public class DocumentExportServiceImpl implements DocumentExportService {
    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown;charset=UTF-8";
    private static final String ZIP_CONTENT_TYPE = "application/zip";
    private static final String ZIP_EXTENSION = ".zip";
    private static final String RESOURCE_DIRECTORY = "resources/";
    private static final String DEFAULT_ATTACHMENT_FILENAME = "attachment.bin";
    private static final String DEFAULT_DOCUMENT_FILENAME = "article";
    private static final Logger LOG = LoggerFactory.getLogger(DocumentExportServiceImpl.class);

    /** 失败时释放压缩器但不写中央目录，禁止把部分资源包装成完整 ZIP。 */
    private static class ExportZip extends ZipOutputStream {
        ExportZip(OutputStream output) {
            super(output, StandardCharsets.UTF_8);
        }

        void release() {
            def.end();
        }
    }

    private final AssetFileService assetFileService;
    private final DocumentRenderer documentRenderer;

    public DocumentExportServiceImpl(
            AssetFileService assetFileService, DocumentRenderer documentRenderer) {
        this.assetFileService = assetFileService;
        this.documentRenderer = documentRenderer;
    }

    @Override
    public DocumentExport prepare(Document document, boolean includeResources) {
        // 1. 检查版本资源和当前资产状态，先于响应提交；缺失资源不能伪装成完整离线导出。
        var resources = documentRenderer.resources(document, false);
        if (resources.stream().anyMatch(resource -> resource.status() != ResourceStatus.READY)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "存在未就绪资源，无法完整导出");
        }
        // 2. 保留正文/携带资源两种选择；只有选择携带资源且确有资源才输出 ZIP。
        boolean zipExport = includeResources && !resources.isEmpty();
        String baseFilename = safeFilename(document.title(), DEFAULT_DOCUMENT_FILENAME);
        var archivePaths = new HashMap<String, String>();
        var markdownDestinations = new HashMap<String, String>();
        var usedFilenames = new HashSet<String>();
        for (var resource : resources) {
            if (zipExport) {
                String filename = uniqueFilename(resource.filename(), usedFilenames);
                archivePaths.put(resource.key(), RESOURCE_DIRECTORY + filename);
                markdownDestinations.put(
                        resource.key(),
                        RESOURCE_DIRECTORY + URLEncoder.encode(filename, StandardCharsets.UTF_8)
                                .replace("+", "%20"));
            } else {
                // 签名 URL 是不透明值，不能解码、重新拼装查询参数或修改编码层数。
                markdownDestinations.put(resource.key(), resource.resourceUrl());
            }
        }
        String markdown = documentRenderer.markdown(document, markdownDestinations);
        if (!zipExport) {
            return new DocumentExport(
                    baseFilename + MARKDOWN_EXTENSION,
                    MARKDOWN_CONTENT_TYPE,
                    output -> {
                        output.write(markdown.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    });
        }
        return new DocumentExport(
                baseFilename + ZIP_EXTENSION,
                ZIP_CONTENT_TYPE,
                output -> {
                    // 3. 正文放 ZIP 根目录；资源平铺到统一目录，逐个读取/关闭，不将文件整体载入内存。
                    var zipArchive = new ExportZip(output);
                    try {
                        zipArchive.putNextEntry(new ZipEntry(baseFilename + MARKDOWN_EXTENSION));
                        zipArchive.write(markdown.getBytes(StandardCharsets.UTF_8));
                        zipArchive.closeEntry();
                        byte[] buffer = new byte[IO_BUFFER_BYTES];
                        for (var resource : resources) {
                            zipArchive.putNextEntry(new ZipEntry(archivePaths.get(resource.key())));
                            try (var assetContent = assetFileService.openReady(resource.assetId())) {
                                long copied = 0;
                                int count;
                                while ((count = assetContent.input().read(buffer)) != -1) {
                                    zipArchive.write(buffer, 0, count);
                                    copied += count;
                                }
                                if (copied != assetContent.size()) {
                                    throw new IOException("Asset length changed during export");
                                }
                            }
                            zipArchive.closeEntry();
                        }
                        // 4. 所有资源写完才完成中央目录；失败保留异常，底层响应流由容器关闭。
                        zipArchive.finish();
                        zipArchive.flush();
                    } catch (IOException | RuntimeException failure) {
                        LOG.warn(
                                "Document export interrupted, documentId={}, contentId={}, failureType={}",
                                document.id(), document.contentId(), failure.getClass().getSimpleName());
                        throw failure;
                    } finally {
                        zipArchive.release();
                    }
                });
    }

    /** 平铺目录内大小写不同也视为冲突，保证解压到 Windows 时不互相覆盖。 */
    private String uniqueFilename(String raw, Set<String> usedFilenames) {
        String filename = safeFilename(raw, DEFAULT_ATTACHMENT_FILENAME);
        int extensionIndex = filename.lastIndexOf('.');
        String stem = extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
        String extension = extensionIndex > 0 ? filename.substring(extensionIndex) : "";
        String candidate = filename;
        int suffix = 2;
        while (!usedFilenames.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = stem + " (" + suffix++ + ")" + extension;
        }
        return candidate;
    }

    /** 只保留安全叶子名与可读 Unicode；非法字符、目录穿越及 Windows 保留名在此统一处理。 */
    private String safeFilename(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}<>:\"|?*]", "_")
                .replaceAll("[ .]+$", "");
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            return fallback;
        }
        if (name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) {
            name = "file-" + name;
        }
        return name;
    }
}
