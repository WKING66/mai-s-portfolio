package dev.amai.portfolio.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 受控读取的对象内容，调用方必须关闭以释放文件句柄或 OSS 连接。 */
public record StoredObjectContent(InputStream inputStream, long byteSize) implements AutoCloseable {
    public StoredObjectContent {
        Objects.requireNonNull(inputStream, StorageMessageConstants.CONTENT_REQUIRED);
        if (byteSize < 0) {
            throw new IllegalArgumentException(StorageMessageConstants.CONTENT_LENGTH_INVALID);
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
