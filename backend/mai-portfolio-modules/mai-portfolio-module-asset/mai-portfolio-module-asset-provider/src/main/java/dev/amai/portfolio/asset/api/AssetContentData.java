package dev.amai.portfolio.asset.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 媒体资产域向已完成授权判断的调用方提供的只读内容。 */
public record AssetContentData(InputStream inputStream, long byteSize, String contentType,
                               String originalFilename, AssetType assetType)
        implements AutoCloseable {
    public AssetContentData {
        Objects.requireNonNull(inputStream, "inputStream");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(assetType, "assetType");
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize must not be negative");
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
