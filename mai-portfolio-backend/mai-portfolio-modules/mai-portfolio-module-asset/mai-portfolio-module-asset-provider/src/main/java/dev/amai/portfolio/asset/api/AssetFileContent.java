package dev.amai.portfolio.asset.api;

import java.io.IOException;
import java.io.InputStream;

/** 受控导出的内容流与元数据；调用方使用 try-with-resources 关闭，不用于普通图片代理。 */
public record AssetFileContent(
        InputStream input,

        long size,

        String filename,

        String contentType)
        implements AutoCloseable {
    @Override
    public void close() throws IOException {
        input.close();
    }
}
