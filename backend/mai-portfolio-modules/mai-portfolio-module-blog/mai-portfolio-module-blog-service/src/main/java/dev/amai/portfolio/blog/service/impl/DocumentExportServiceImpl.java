package dev.amai.portfolio.blog.service.impl;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.*;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.blog.component.DocumentRenderer;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.service.DocumentExportService;
import dev.amai.portfolio.web.exception.*;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 基于已授权 Document 生成输出计划；普通展示链接与受保护 ZIP 字节读取分别处理。 */
@Service
public class DocumentExportServiceImpl implements DocumentExportService {
    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown;charset=UTF-8";
    private static final String ZIP_CONTENT_TYPE = "application/zip";
    private static final String ZIP_EXTENSION = ".zip";
    private static final String RESOURCE_DIRECTORY = "resources/";
    private static final String DEFAULT_ATTACHMENT_FILENAME = "attachment.bin";
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DocumentExportServiceImpl.class);

    /** 失败时释放压缩器但不写中央目录，禁止把部分资源包装成完整 ZIP。 */
    private static class ExportZip extends ZipOutputStream {
        ExportZip(java.io.OutputStream output) {
            super(output, StandardCharsets.UTF_8);
        }

        void release() {
            def.end();
        }
    }

    private final AssetFileService files;
    private final DocumentRenderer renderer;

    public DocumentExportServiceImpl(AssetFileService files, DocumentRenderer renderer) {
        this.files = files;
        this.renderer = renderer;
    }

    private String safeFilename(String raw) {
        // ZIP 内只使用安全叶子文件名，避免目录穿越及 Windows 保留名；key 目录防止同名覆盖。
        if (raw == null || raw.isBlank()) return DEFAULT_ATTACHMENT_FILENAME;
        String name = raw.replace('\\', '/');
        name =
                name.substring(name.lastIndexOf('/') + 1)
                        .replaceAll("[\\p{Cntrl}<>:\"|?*]", "_")
                        .replaceAll("[ .]+$", "");
        if (name.isBlank() || name.equals(".") || name.equals("..")) return DEFAULT_ATTACHMENT_FILENAME;
        if (name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?"))
            name = "file-" + name;
        return name;
    }

    @Override
    public DocumentExport prepare(Document document, boolean includeResources) {
        // 此处仅准备元数据和正文。所有资源就绪检查先于响应提交，不能生成缺资源的“成功导出”。
        var resources = renderer.resources(document, false);
        if (resources.stream().anyMatch(r -> r.status() != ResourceStatus.READY))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "存在未就绪资源，无法完整导出");
        // 选择携带资源且确有资源才输出 ZIP；纯正文即使勾选也仍是 .md。
        boolean zip = includeResources && !resources.isEmpty();
        Map<String, String> paths = new HashMap<>();
        for (var resource : resources)
            paths.put(
                    resource.key(),
                    zip
                            ? RESOURCE_DIRECTORY
                                    + resource.key()
                                    + "/"
                                    + safeFilename(resource.filename())
                            : resource.resourceUrl());
        // 只替换本次输出中的地址：.md 用临时签名 URL，ZIP 用包内相对路径，不回写 Document。
        String markdown = renderer.markdown(document, paths);
        String base = "document-" + document.id() + "-v" + document.version();
        if (!zip)
            return new DocumentExport(
                    base + MARKDOWN_EXTENSION,
                    MARKDOWN_CONTENT_TYPE,
                    output -> {
                        // 正文已按导入/编辑上限控制；资源二进制从不整体加载。
                        output.write(markdown.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    });
        return new DocumentExport(
                base + ZIP_EXTENSION,
                ZIP_CONTENT_TYPE,
                output -> {
                    // 完整写入后显式 finish 完成中央目录；底层响应流由 Servlet 容器关闭。
                    var archive = new ExportZip(output);
                    try {
                        archive.putNextEntry(new ZipEntry(base + MARKDOWN_EXTENSION));
                        archive.write(markdown.getBytes(StandardCharsets.UTF_8));
                        archive.closeEntry();
                        byte[] buffer = new byte[IO_BUFFER_BYTES];
                        for (var resource : resources) {
                            archive.putNextEntry(new ZipEntry(paths.get(resource.key())));
                            try (var file = files.openReady(resource.assetId())) {
                                long copied = 0;
                                int count;
                                while ((count = file.input().read(buffer)) != -1) {
                                    archive.write(buffer, 0, count);
                                    copied += count;
                                }
                                // 实际读取长度也要一致，不能把截断的对象封装成完整 ZIP。
                                if (copied != file.size())
                                    throw new java.io.IOException(
                                            "Asset length changed during export");
                            }
                            archive.closeEntry();
                        }
                        archive.finish();
                        archive.flush();
                    } catch (java.io.IOException | RuntimeException failure) {
                        LOG.warn(
                                "Document export interrupted, documentId={}, versionId={},"
                                        + " failureType={}",
                                document.id(),
                                document.versionId(),
                                failure.getClass().getSimpleName());
                        throw failure;
                    } finally {
                        archive.release();
                    }
                });
    }
}
