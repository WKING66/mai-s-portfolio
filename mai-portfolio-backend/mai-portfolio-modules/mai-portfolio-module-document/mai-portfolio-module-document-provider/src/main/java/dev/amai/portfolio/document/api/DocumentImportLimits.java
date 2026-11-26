package dev.amai.portfolio.document.api;

/** 调用方读取上传字节时使用的文档输入限制。 */
public interface DocumentImportLimits {
    int getMaxContentBytes();

    int getMaxResources();

    int getMaxImportBytes();
}
