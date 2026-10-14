package dev.amai.portfolio.asset.api;

import java.io.IOException;
import java.io.InputStream;

public record AssetFileContent(InputStream input, long size, String filename, String contentType)
        implements AutoCloseable {
    @Override
    public void close() throws IOException {
        input.close();
    }
}
