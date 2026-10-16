package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.IO_BUFFER_BYTES;
import static org.springframework.http.MediaType.IMAGE_JPEG_VALUE;
import static org.springframework.http.MediaType.IMAGE_PNG_VALUE;

import dev.amai.portfolio.blog.config.DocumentProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;

/** 单跳 HTTP 响应的重定向或有界图片读取；不发起请求、不决定下一跳是否允许访问。 */
final class RemoteResourceResponseHandler implements HttpClientResponseHandler<RemoteResourceResponseHandler.Result> {
    private final URI requestUri;
    private final int maxBytes;
    private final long deadlineNanos;
    private final DocumentProperties documentProperties;

    record Result(
            URI redirect,

            String mime,

            byte[] bytes) {}

    RemoteResourceResponseHandler(
            URI requestUri, int maxBytes, long deadlineNanos, DocumentProperties documentProperties) {
        this.requestUri = requestUri;
        this.maxBytes = maxBytes;
        this.deadlineNanos = deadlineNanos;
        this.documentProperties = documentProperties;
    }

    @Override
    public Result handleResponse(ClassicHttpResponse response) throws IOException {
        int statusCode = response.getCode();
        if (statusCode >= HttpStatus.SC_MULTIPLE_CHOICES && statusCode < HttpStatus.SC_BAD_REQUEST) {
            return redirect(response);
        }
        if (statusCode != HttpStatus.SC_OK || response.getEntity() == null) {
            throw new IOException("Resource fetch failed");
        }
        var entity = response.getEntity();
        byte[] bytes = readContent(entity);
        String mime = entity.getContentType();
        if (mime != null) {
            mime = mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        }
        if (!IMAGE_PNG_VALUE.equals(mime) && !IMAGE_JPEG_VALUE.equals(mime)) {
            throw new IOException("Unsupported image type");
        }
        return new Result(null, mime, bytes);
    }

    private Result redirect(ClassicHttpResponse response) throws IOException {
        var location = response.getFirstHeader(HttpHeaders.LOCATION);
        if (location == null) {
            throw new IOException("Missing redirect location");
        }
        try {
            return new Result(requestUri.resolve(location.getValue()), null, null);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid redirect URI", failure);
        }
    }

    private byte[] readContent(HttpEntity entity) throws IOException {
        if (entity.getContentLength() > maxBytes) {
            throw new IOException("Resource too large");
        }
        // 声明长度、累计长度与读取期限分别受限；所有跳转共用原来的文档绝对预算。
        long readDeadlineNanos = Math.min(
                deadlineNanos,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(documentProperties.getReadTimeoutMs()));
        byte[] bytes;
        try (var input = entity.getContent(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                if (System.nanoTime() > readDeadlineNanos || output.size() + bytesRead > maxBytes) {
                    throw new IOException("Resource read limit exceeded");
                }
                output.write(buffer, 0, bytesRead);
            }
            bytes = output.toByteArray();
        }
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IOException("Resource size invalid");
        }
        return bytes;
    }
}
