package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.IO_BUFFER_BYTES;
import static org.springframework.http.MediaType.IMAGE_JPEG_VALUE;
import static org.springframework.http.MediaType.IMAGE_PNG_VALUE;

import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;

import jakarta.annotation.PreDestroy;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.util.Locale;

/** DNS 校验发生在实际建立连接的解析器内，重定向逐跳校验；不继承 Cookie/凭据/系统代理。 */
@Component
public class RemoteResourceFetcher implements AutoCloseable {
    private static final int UNSPECIFIED_PORT = -1, HTTP_PORT = 80, HTTPS_PORT = 443;
    private final DocumentProperties limits;
    private final CloseableHttpClient client;

    public RemoteResourceFetcher(DocumentProperties limits) {
        this.limits = limits;
        DnsResolver resolver =
                new DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        InetAddress[] addresses = InetAddress.getAllByName(host);
                        for (var address : addresses)
                            if (!publicAddress(address))
                                throw new UnknownHostException("Non-public destination");
                        return addresses;
                    }

                    @Override
                    public String resolveCanonicalHostname(String host)
                            throws UnknownHostException {
                        return host;
                    }
                };
        client =
                HttpClients.custom()
                        .setConnectionManager(
                                PoolingHttpClientConnectionManagerBuilder.create()
                                        .setDnsResolver(resolver)
                                        .build())
                        .disableRedirectHandling()
                        .disableCookieManagement()
                        .disableAutomaticRetries()
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setConnectTimeout(
                                                Timeout.ofMilliseconds(
                                                        limits.getConnectTimeoutMs()))
                                        .setResponseTimeout(
                                                Timeout.ofMilliseconds(limits.getReadTimeoutMs()))
                                        .setConnectionRequestTimeout(
                                                Timeout.ofMilliseconds(
                                                        limits.getConnectTimeoutMs()))
                                        .build())
                        .build();
    }

    /**
     * 只抓取已由调用方确认使用权的网络图片；重定向、读取与本篇文档其他图片共享绝对时间预算。
     * MIME 是前置筛选，真实图片解码、尺寸和重编码继续由 Asset 层负责。
     */
    public DocumentInputResource fetch(String source, int maxBytes, long deadlineNanos)
            throws IOException {
        URI target;
        try {
            target = URI.create(source);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid resource URI");
        }
        for (int redirects = 0; redirects <= limits.getMaxRedirects(); redirects++) {
            validateUri(target);
            URI requested = target;
            long remainingMs =
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                            deadlineNanos - System.nanoTime());
            if (remainingMs < 1) throw new IOException("Document fetch budget exhausted");
            var request = new HttpGet(requested);
            request.setConfig(
                    RequestConfig.custom()
                            .setConnectTimeout(
                                    Timeout.ofMilliseconds(
                                            Math.min(limits.getConnectTimeoutMs(), remainingMs)))
                            .setConnectionRequestTimeout(
                                    Timeout.ofMilliseconds(
                                            Math.min(limits.getConnectTimeoutMs(), remainingMs)))
                            .setResponseTimeout(
                                    Timeout.ofMilliseconds(
                                            Math.min(limits.getReadTimeoutMs(), remainingMs)))
                            .build());
            Fetch result =
                    client.execute(
                            request,
                            response -> {
                                int code = response.getCode();
                                if (code >= HttpStatus.SC_MULTIPLE_CHOICES && code < HttpStatus.SC_BAD_REQUEST) {
                                    var location = response.getFirstHeader(HttpHeaders.LOCATION);
                                    if (location == null)
                                        throw new IOException("Missing redirect location");
                                    try {
                                        return new Fetch(
                                                requested.resolve(location.getValue()), null, null);
                                    } catch (IllegalArgumentException failure) {
                                        throw new IOException("Invalid redirect URI", failure);
                                    }
                                }
                                if (code != HttpStatus.SC_OK || response.getEntity() == null)
                                    throw new IOException("Resource fetch failed");
                                var entity = response.getEntity();
                                if (entity.getContentLength() > maxBytes)
                                    throw new IOException("Resource too large");
                                byte[] bytes;
                                long deadline =
                                        Math.min(
                                                deadlineNanos,
                                                System.nanoTime()
                                                        + java.util.concurrent.TimeUnit.MILLISECONDS
                                                                .toNanos(
                                                                        limits.getReadTimeoutMs()));
                                try (var input = entity.getContent();
                                        var output = new ByteArrayOutputStream()) {
                                    byte[] buffer = new byte[IO_BUFFER_BYTES];
                                    int n;
                                    while ((n = input.read(buffer)) != -1) {
                                        if (System.nanoTime() > deadline
                                                || output.size() + n > maxBytes)
                                            throw new IOException("Resource read limit exceeded");
                                        output.write(buffer, 0, n);
                                    }
                                    bytes = output.toByteArray();
                                }
                                if (bytes.length == 0 || bytes.length > maxBytes)
                                    throw new IOException("Resource size invalid");
                                String mime = entity.getContentType();
                                if (mime != null)
                                    mime = mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                                if (!IMAGE_PNG_VALUE.equals(mime) && !IMAGE_JPEG_VALUE.equals(mime))
                                    throw new IOException("Unsupported image type");
                                return new Fetch(null, mime, bytes);
                            });
            if (result.redirect() == null)
                return new DocumentInputResource(
                        source,
                        result.mime(),
                        result.bytes(),
                        dev.amai.portfolio.blog.enums.ResourceSource.REMOTE);
            target = result.redirect();
        }
        throw new IOException("Too many redirects");
    }

    /** 仅允许 HTTP(S) 默认端口；URL 凭据与 fragment 不属于资源抓取协议。 */
    public static void validateUri(URI uri) throws IOException {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !(uri.getPort() == UNSPECIFIED_PORT || uri.getPort() == HTTP_PORT || uri.getPort() == HTTPS_PORT))
            throw new IOException("Unsafe resource URI");
    }

    /** 当前采用保守公网筛选；数值为 IP 前缀/位掩码，不是文档类型或业务状态编码。 */
    public static boolean publicAddress(InetAddress a) {
        if (a.isAnyLocalAddress()
                || a.isLoopbackAddress()
                || a.isLinkLocalAddress()
                || a.isSiteLocalAddress()
                || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            // 转成无符号 IPv4 字节；额外屏蔽 CGNAT、基准测试及含保留地址的前缀段。
            // 192.0、198.51、203.0 等按前两字节保守排除，范围比对应文档示例网段更宽。
            int x = b[0] & 255, y = b[1] & 255;
            return !(x == 0
                    || x >= 224
                    || (x == 100 && y >= 64 && y <= 127)
                    || (x == 192 && (y == 0 || y == 2))
                    || (x == 198 && (y == 18 || y == 19 || y == 51))
                    || (x == 203 && y == 0));
        }
        // 仅接受 2000::/3 全局单播；排除 2002::/16、2001:db8::/32 和 2001:00xx 段。
        return (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20
                        && (b[1] & 255) == 0x01
                        && ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8 || b[2] == 0));
    }

    @PreDestroy
    @Override
    public void close() throws IOException {
        client.close();
    }

    private record Fetch(URI redirect, String mime, byte[] bytes) {}
}
